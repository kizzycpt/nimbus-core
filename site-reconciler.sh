#!/usr/bin/env bash
#
# Nimbus Core — reconcile customer site containers with what is on disk.
#
# The API never talks to Docker. Handing the Docker socket to a network-facing
# application is handing out root on the host, so instead the API owns the
# filesystem and this script — running on the host, where Docker access is
# already assumed — owns container lifecycle.
#
# It is a reconciler, not a queue: it compares desired state (directories under
# the sites dir) with actual state (containers labelled nimbus.role=site) and
# fixes the difference. That makes it idempotent and self-healing. Run it as
# often as you like.
#
#   ./site-reconciler.sh              reconcile once
#   ./site-reconciler.sh --dry-run    show what would change
#   ./site-reconciler.sh --watch      loop every INTERVAL seconds
#   ./site-reconciler.sh --install    install the systemd timer (needs root)
#
set -euo pipefail

cd "$(dirname "$0")"
REPO_DIR="$(pwd)"

# Where the API writes site files. Must match NIMBUS_SITES_DIR in .env.
SITES_DIR="${NIMBUS_SITES_DIR:-$REPO_DIR/data/sites}"
NETWORK="${NIMBUS_SITE_NETWORK:-nimbus-core_sitenet}"
IMAGE="${NIMBUS_SITE_IMAGE:-nginx:stable}"
SITE_CONF="$REPO_DIR/Frontend/nginx/site-container.conf"
SITE_MAIN_CONF="$REPO_DIR/Frontend/nginx/site-nginx.conf"
INTERVAL="${NIMBUS_RECONCILE_INTERVAL:-30}"

# Resource ceilings per customer container. One site must not be able to starve
# the box or the other sites on it.
#
# Worker count is pinned to 1 in site-nginx.conf rather than left at nginx's
# `auto`, which means one worker per HOST core — 28 workers per static site on a
# 28-core box. That one setting is the difference between ~20MB and ~3MB per
# site, and so between roughly 45 and 300 sites per gigabyte.
MEM_LIMIT="${NIMBUS_SITE_MEMORY:-64m}"
PIDS_LIMIT="${NIMBUS_SITE_PIDS:-64}"
CPU_LIMIT="${NIMBUS_SITE_CPUS:-0.25}"

SLUG_RE='^[a-z0-9][a-z0-9-]{1,30}$'

DRY_RUN=0
WATCH=0

c_red() { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn() { printf '\033[32m%s\033[0m\n' "$*"; }
c_ylw() { printf '\033[33m%s\033[0m\n' "$*"; }
die()   { c_red "error: $*" >&2; exit 1; }
log()   { printf '%s  %s\n' "$(date -u +%H:%M:%S)" "$*"; }

#------------------------------------------------------------------
# systemd install
#------------------------------------------------------------------
install_timer() {
  [[ $EUID -eq 0 ]] || die "--install needs root (sudo $0 --install)"

  cat > /etc/systemd/system/nimbus-sites.service <<EOF
[Unit]
Description=Reconcile Nimbus customer site containers
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
WorkingDirectory=$REPO_DIR
Environment=NIMBUS_SITES_DIR=$SITES_DIR
ExecStart=$REPO_DIR/site-reconciler.sh
EOF

  cat > /etc/systemd/system/nimbus-sites.timer <<EOF
[Unit]
Description=Reconcile Nimbus customer sites every ${INTERVAL}s

[Timer]
OnBootSec=30s
OnUnitActiveSec=${INTERVAL}s
AccuracySec=5s

[Install]
WantedBy=timers.target
EOF

  systemctl daemon-reload
  systemctl enable --now nimbus-sites.timer
  c_grn "Installed. Status: systemctl status nimbus-sites.timer"
  exit 0
}

for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --watch)   WATCH=1 ;;
    --install) install_timer ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) die "unknown option: $arg" ;;
  esac
done

#------------------------------------------------------------------
# Preflight
#------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "docker is not on PATH"
docker info >/dev/null 2>&1 || die "cannot talk to the docker daemon"
[[ -f "$SITE_CONF" ]] || die "missing $SITE_CONF"
[[ -f "$SITE_MAIN_CONF" ]] || die "missing $SITE_MAIN_CONF"
[[ -d "$SITES_DIR" ]] || die "sites dir does not exist: $SITES_DIR (start the stack first)"

docker network inspect "$NETWORK" >/dev/null 2>&1 \
  || die "network '$NETWORK' not found — bring the stack up first (./run.sh up)"

#------------------------------------------------------------------
# Capability probe
#
# Inside an LXC container, cgroup controllers are only available if the host
# delegated them. When they are missing, `docker run --memory` fails outright
# rather than degrading — which would mean no customer site could be created at
# all. Probe once, drop the flags that are unsupported, and say so loudly:
# running without limits is a real reduction in isolation, not a detail.
#------------------------------------------------------------------
DOCKER_INFO="$(docker info 2>&1)"
SUPPORTS_MEMORY=1; SUPPORTS_CPU=1; SUPPORTS_PIDS=1

grep -qi "No memory limit support"     <<<"$DOCKER_INFO" && SUPPORTS_MEMORY=0
grep -qi "No swap limit support"       <<<"$DOCKER_INFO" || true
grep -qi "No cpu cfs quota support"    <<<"$DOCKER_INFO" && SUPPORTS_CPU=0
grep -qi "No pids limit support"       <<<"$DOCKER_INFO" && SUPPORTS_PIDS=0

if (( ! SUPPORTS_MEMORY || ! SUPPORTS_CPU || ! SUPPORTS_PIDS )); then
  c_ylw "warning: this host cannot enforce some container limits."
  (( SUPPORTS_MEMORY )) || c_ylw "  - memory limit unavailable: one site can exhaust the box's RAM"
  (( SUPPORTS_CPU ))    || c_ylw "  - cpu quota unavailable: one site can monopolise the CPU"
  (( SUPPORTS_PIDS ))   || c_ylw "  - pids limit unavailable: a fork bomb is not contained"
  c_ylw "  Under Proxmox LXC this usually means cgroups were not delegated."
  c_ylw "  See local-notes/proxmox-lxc.txt. Continuing without those limits."
fi

#------------------------------------------------------------------
# Reconcile
#------------------------------------------------------------------
container_for() { echo "nimbus-site-$1"; }

running_slugs() {
  docker ps -a --filter "label=nimbus.role=site" \
    --format '{{.Label "nimbus.slug"}}' 2>/dev/null | grep -v '^$' || true
}

desired_slugs() {
  [[ -d "$SITES_DIR" ]] || return 0
  for dir in "$SITES_DIR"/*/; do
    [[ -d "$dir" ]] || continue
    slug="$(basename "$dir")"
    # Anything that is not a valid slug is not ours; leave it alone.
    [[ "$slug" =~ $SLUG_RE ]] || continue
    [[ -d "$dir/public" ]] || continue
    echo "$slug"
  done
}

create_site() {
  local slug="$1" name public
  name="$(container_for "$slug")"
  public="$SITES_DIR/$slug/public"

  if (( DRY_RUN )); then
    c_ylw "  would create $name -> $public"
    return 0
  fi

  # Limits that the host cannot enforce are omitted rather than passed and
  # rejected; everything else (read-only rootfs, dropped caps, no new privs)
  # works regardless of cgroup delegation.
  local args=(
    -d
    --name "$name"
    --label nimbus.role=site
    --label "nimbus.slug=$slug"
    --network "$NETWORK"
    --restart unless-stopped
    --read-only
    --cap-drop ALL
    --cap-add CHOWN --cap-add SETGID --cap-add SETUID --cap-add NET_BIND_SERVICE
    --security-opt no-new-privileges:true
    # json-file does not rotate on its own. A site under load, or one being
    # scanned by bots, would otherwise write access logs until the disk is full.
    --log-driver json-file
    --log-opt max-size=5m
    --log-opt max-file=2
    --tmpfs /var/cache/nginx --tmpfs /var/run --tmpfs /tmp
    -v "$public:/usr/share/nginx/html:ro"
    -v "$SITE_MAIN_CONF:/etc/nginx/nginx.conf:ro"
    -v "$SITE_CONF:/etc/nginx/conf.d/default.conf:ro"
  )

  (( SUPPORTS_MEMORY )) && args+=( --memory "$MEM_LIMIT" )
  (( SUPPORTS_PIDS ))   && args+=( --pids-limit "$PIDS_LIMIT" )
  (( SUPPORTS_CPU ))    && args+=( --cpus "$CPU_LIMIT" )

  docker run "${args[@]}" "$IMAGE" >/dev/null

  c_grn "  created $name"
}

remove_site() {
  local slug="$1" name
  name="$(container_for "$slug")"

  if (( DRY_RUN )); then
    c_ylw "  would remove $name"
    return 0
  fi

  docker rm -f "$name" >/dev/null 2>&1 || true
  c_grn "  removed $name"
}

reconcile() {
  local desired actual created=0 removed=0 started=0
  desired="$(desired_slugs | sort -u)"
  actual="$(running_slugs | sort -u)"

  # Present on disk, no container yet -> create.
  while read -r slug; do
    [[ -n "$slug" ]] || continue
    if ! grep -qxF "$slug" <<<"$actual"; then
      create_site "$slug"
      created=$((created+1))
    else
      # Container exists but is not running (host reboot, crash, OOM kill).
      local name state
      name="$(container_for "$slug")"
      state="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || echo missing)"
      if [[ "$state" != "running" ]]; then
        if (( DRY_RUN )); then
          c_ylw "  would start $name (currently $state)"
        else
          docker start "$name" >/dev/null 2>&1 && c_grn "  started $name (was $state)"
        fi
        started=$((started+1))
      fi
    fi
  done <<<"$desired"

  # Container exists, directory gone -> the site was deleted through the API.
  while read -r slug; do
    [[ -n "$slug" ]] || continue
    if ! grep -qxF "$slug" <<<"$desired"; then
      remove_site "$slug"
      removed=$((removed+1))
    fi
  done <<<"$actual"

  local total
  total=$(grep -c . <<<"$desired" || true)
  log "sites: $total desired, +$created created, ~$started restarted, -$removed removed"
}

if (( WATCH )); then
  log "watching $SITES_DIR every ${INTERVAL}s (ctrl-c to stop)"
  while true; do
    reconcile
    sleep "$INTERVAL"
  done
else
  reconcile
fi
