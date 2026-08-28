#!/usr/bin/env bash
#
# Nimbus Core — one-shot setup for a fresh Debian machine (arm64 or amd64).
#
# Takes a box with nothing on it to a running stack:
#   1. installs Docker Engine + the compose plugin from Docker's own repo
#   2. adds you to the docker group
#   3. generates .env with strong random secrets
#   4. builds the images and starts everything
#   5. verifies the API actually answers
#
#   sudo ./bootstrap.sh              full setup
#   ./bootstrap.sh --skip-docker     Docker is already installed
#   ./bootstrap.sh --check           report what is present, change nothing
#
# Tested against Debian 12 (bookworm) and Debian 13 (trixie). Ubuntu works too.
#
set -euo pipefail

cd "$(dirname "$0")"
REPO_DIR="$(pwd)"

SKIP_DOCKER=0
CHECK_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --skip-docker) SKIP_DOCKER=1 ;;
    --check)       CHECK_ONLY=1 ;;
    -h|--help)     sed -n '2,18p' "$0"; exit 0 ;;
    *)             echo "unknown option: $arg" >&2; exit 1 ;;
  esac
done

c_red() { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn() { printf '\033[32m%s\033[0m\n' "$*"; }
c_ylw() { printf '\033[33m%s\033[0m\n' "$*"; }
step()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die()   { c_red "error: $*" >&2; exit 1; }

# Every privileged command goes through this, so the script works both as root
# and as a normal user with sudo.
if [[ $EUID -eq 0 ]]; then
  SUDO=""
  TARGET_USER="${SUDO_USER:-root}"
else
  command -v sudo >/dev/null 2>&1 || die "not root and sudo is not installed"
  SUDO="sudo"
  TARGET_USER="$USER"
fi

#------------------------------------------------------------------
# Preflight
#------------------------------------------------------------------
step "Checking the machine"

[[ -f /etc/os-release ]] || die "cannot read /etc/os-release — is this Debian?"
# shellcheck disable=SC1091
. /etc/os-release

ARCH="$(dpkg --print-architecture 2>/dev/null || uname -m)"
echo "  distro : ${PRETTY_NAME:-unknown}"
echo "  id     : ${ID:-unknown} (${VERSION_CODENAME:-no codename})"
echo "  arch   : ${ARCH}"

case "${ID:-}" in
  debian|raspbian|ubuntu) ;;
  *) c_ylw "  warning: this script targets Debian/Ubuntu; continuing anyway" ;;
esac

case "$ARCH" in
  arm64|aarch64|amd64|x86_64) ;;
  *) die "unsupported architecture '$ARCH' — the base images publish arm64 and amd64 only" ;;
esac

# Where this is running determines what can go wrong: a VM behaves like bare
# metal, an LXC container has caveats around cgroups and UID shifting.
IN_LXC=0
VIRT="unknown"
if command -v systemd-detect-virt >/dev/null 2>&1; then
  # It prints "none" AND exits 1 on bare metal, so `|| echo none` would append
  # a second line. Take the output and ignore the status.
  VIRT="$(systemd-detect-virt 2>/dev/null)" || true
  VIRT="${VIRT:-none}"
fi
if [ -f /run/systemd/container ] && grep -qi lxc /run/systemd/container 2>/dev/null; then
  IN_LXC=1
elif grep -qa 'container=lxc' /proc/1/environ 2>/dev/null; then
  IN_LXC=1
elif [ "$VIRT" = "lxc" ]; then
  IN_LXC=1
fi

case "$VIRT" in
  kvm|qemu)
    echo "  runtime: VM ($VIRT) — full cgroup and storage support expected"
    if ! systemctl is-active --quiet qemu-guest-agent 2>/dev/null; then
      c_ylw "           qemu-guest-agent is not running. Install it so Proxmox can"
      c_ylw "           shut this VM down cleanly:  apt-get install -y qemu-guest-agent"
    fi ;;
  none|"")
    echo "  runtime: bare metal" ;;
  *)
    echo "  runtime: $VIRT" ;;
esac

if (( IN_LXC )); then
  c_ylw "  runtime: LXC container — see local-notes/proxmox-lxc.txt"
  if [ -f /proc/self/uid_map ] && ! grep -qE '^\s*0\s+0\s' /proc/self/uid_map; then
    c_ylw "           unprivileged (UIDs are shifted — section 5 of that file)"
  fi
fi

#------------------------------------------------------------------
# Docker health checks that matter inside LXC
#
# Both of these are silent failures: vfs just makes everything slow, and
# missing cgroup delegation just means the limits you think you set are not
# there. Surface them explicitly.
#------------------------------------------------------------------
check_docker_health() {
  command -v docker >/dev/null 2>&1 || return 0
  ($SUDO docker info >/dev/null 2>&1 || docker info >/dev/null 2>&1) || return 0

  local dinfo driver
  dinfo="$( { docker info 2>&1 || $SUDO docker info 2>&1; } )"

  driver="$(grep -i 'Storage Driver:' <<<"$dinfo" | awk '{print $3}')"
  case "$driver" in
    overlay2|overlayfs)
      c_grn "  storage driver: $driver" ;;
    vfs)
      c_red "  storage driver: vfs — builds will be extremely slow and disk usage"
      c_red "  will multiply. This happens when Docker runs on a ZFS-backed LXC."
      c_red "  Fix before continuing: local-notes/proxmox-lxc.txt section 3" ;;
    *)
      c_ylw "  storage driver: ${driver:-unknown}" ;;
  esac

  if grep -qiE "no (memory|cpu cfs quota|pids) limit support" <<<"$dinfo"; then
    c_ylw "  cgroup limits: NOT fully available"
    grep -iE "^WARNING.*(memory|cpu|pids)" <<<"$dinfo" | sed 's/^/    /'
    c_ylw "  Customer sites will run without resource caps — one site could"
    c_ylw "  exhaust this container. See local-notes/proxmox-lxc.txt section 4."
  else
    c_grn "  cgroup limits: available"
  fi
}

if (( CHECK_ONLY )); then
  step "Check-only mode"
  command -v docker >/dev/null 2>&1 \
    && c_grn "  docker: $(docker --version)" \
    || c_ylw "  docker: not installed"
  docker compose version >/dev/null 2>&1 \
    && c_grn "  compose: $(docker compose version --short)" \
    || c_ylw "  compose: not installed"
  [[ -f .env ]] && c_grn "  .env: present" || c_ylw "  .env: will be generated"
  check_docker_health
  exit 0
fi

#------------------------------------------------------------------
# Docker
#------------------------------------------------------------------
if (( SKIP_DOCKER )) || { command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; }; then
  step "Docker already present — skipping install"
  docker --version
else
  step "Installing Docker Engine + compose plugin"

  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq ca-certificates curl gnupg

  $SUDO install -m 0755 -d /etc/apt/keyrings

  # Raspbian publishes under the Debian repo; everything else uses its own ID.
  DOCKER_DISTRO="$ID"
  [[ "$ID" == "raspbian" ]] && DOCKER_DISTRO="debian"

  if [[ ! -f /etc/apt/keyrings/docker.asc ]]; then
    curl -fsSL "https://download.docker.com/linux/${DOCKER_DISTRO}/gpg" \
      | $SUDO tee /etc/apt/keyrings/docker.asc >/dev/null
    $SUDO chmod a+r /etc/apt/keyrings/docker.asc
  fi

  echo "deb [arch=${ARCH} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${DOCKER_DISTRO} ${VERSION_CODENAME} stable" \
    | $SUDO tee /etc/apt/sources.list.d/docker.list >/dev/null

  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq \
    docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

  $SUDO systemctl enable --now docker

  c_grn "  installed: $(docker --version)"
fi

check_docker_health

#------------------------------------------------------------------
# docker group
#------------------------------------------------------------------
if [[ "$TARGET_USER" != "root" ]]; then
  if id -nG "$TARGET_USER" | tr ' ' '\n' | grep -qx docker; then
    step "User '$TARGET_USER' is already in the docker group"
  else
    step "Adding '$TARGET_USER' to the docker group"
    $SUDO usermod -aG docker "$TARGET_USER"
    c_ylw "  Group membership applies to NEW logins."
    c_ylw "  This run continues with sudo; log out and back in for plain 'docker' to work."
  fi
fi

# Pick the invocation that works right now, group change or not.
if docker info >/dev/null 2>&1; then
  DOCKER_OK=1
elif $SUDO docker info >/dev/null 2>&1; then
  DOCKER_OK=1
  export NIMBUS_SUDO_DOCKER=1
else
  die "the docker daemon is not reachable — try: $SUDO systemctl start docker"
fi
(( DOCKER_OK )) || die "docker unavailable"

#------------------------------------------------------------------
# Secrets
#------------------------------------------------------------------
step "Preparing secrets"

if [[ -f .env ]]; then
  c_grn "  .env already exists — leaving it alone"
else
  gen_secret() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-48}"; }
  umask 077
  cat > .env <<EOF
# Generated by bootstrap.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)
# Live credentials. Gitignored — keep it that way.

POSTGRES_DB=nimbus
POSTGRES_USER=nimbus
POSTGRES_PASSWORD=$(gen_secret 48)

# HS256 signing key. Rotating it invalidates every issued API token.
JWT_SECRET=$(gen_secret 64)
JWT_EXPIRATION_MS=600000

# Idle timeout for browser session cookies, in minutes.
APP_SESSION_TTL_MINUTES=720

# Flip to true once a TLS front end is in place (adds Secure to cookies).
APP_SECURE_COOKIES=false

# Where customer site files live on the host. Point this at your data disk.
# Must be ext4/xfs — exFAT and FAT32 cannot store file ownership.
NIMBUS_SITES_DIR=./data/sites

# Storage sold per account, across all their sites.
APP_SITES_QUOTA_BYTES=104857600
APP_SITES_MAX_FILE_BYTES=10485760
APP_SITES_MAX_PER_USER=3

# Leave empty for same-origin. Example for a tunnel: https://*.trycloudflare.com
APP_CORS_ALLOWED_ORIGIN_PATTERNS=
EOF
  chmod 600 .env
  c_grn "  wrote .env with fresh random secrets (mode 600)"
fi

#------------------------------------------------------------------
# Site storage
#------------------------------------------------------------------
step "Preparing site storage"

# shellcheck disable=SC1091
SITES_DIR="$(grep -E '^NIMBUS_SITES_DIR=' .env | cut -d= -f2- || true)"
SITES_DIR="${SITES_DIR:-./data/sites}"
mkdir -p "$SITES_DIR"

# Warn loudly about a filesystem that cannot express ownership — the failure it
# causes later looks like a permissions bug and wastes an afternoon.
FSTYPE="$(df --output=fstype "$SITES_DIR" 2>/dev/null | tail -1 | tr -d ' ')"
case "$FSTYPE" in
  ext2|ext3|ext4|xfs|btrfs|zfs|overlay|tmpfs)
    echo "  $SITES_DIR ($FSTYPE)" ;;
  exfat|vfat|msdos|fuseblk|ntfs)
    c_red "  $SITES_DIR is $FSTYPE, which cannot store file ownership."
    c_red "  Customer site permissions will not work. Reformat as ext4:"
    c_red "      sudo mkfs.ext4 -L nimbus /dev/sdX1"
    c_ylw "  Continuing anyway — see local-notes/flash-drive-storage.txt" ;;
  *)
    c_ylw "  $SITES_DIR is '$FSTYPE' — unrecognised, proceeding" ;;
esac

$SUDO chown -R 10001:10001 "$SITES_DIR" 2>/dev/null \
  || c_ylw "  could not chown $SITES_DIR — run.sh will run the backend as its current owner instead"

#------------------------------------------------------------------
# Build and start
#------------------------------------------------------------------
step "Building and starting the stack (first run pulls base images)"

chmod +x run.sh harden-ssh.sh 2>/dev/null || true

if [[ "${NIMBUS_SUDO_DOCKER:-0}" == "1" ]]; then
  $SUDO ./run.sh up
else
  ./run.sh up
fi

#------------------------------------------------------------------
# Done
#------------------------------------------------------------------
step "Setup complete"
cat <<EOF

  Nimbus Core is running at  http://127.0.0.1:8080/

  Next steps
    Verify everything        ./verify.sh
    Publish customer sites   sudo ./site-reconciler.sh --install
    Follow logs              ./run.sh logs
    Stop                     ./run.sh down

  Customer websites run as their own containers. The reconciler builds them
  from what is on disk; install the systemd timer above and it keeps them in
  sync every 30 seconds, including restarting any that die.

  Reachable only from this machine by design. To expose it safely, read
  local-notes/ssh-remote-access.txt — it covers Tailscale and Cloudflare
  Tunnel, neither of which needs an open inbound port.

  To harden this box's own SSH daemon:  sudo ./harden-ssh.sh

EOF
c_grn "Done."
