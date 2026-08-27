#!/usr/bin/env bash
#
# Harden the HOST's SSH daemon for internet-facing access.
#
# Run this on the machine you SSH *into*, as root (or with sudo).
# It will NOT disable password login unless it can first prove you have a
# working public key installed — that check is what stops you locking
# yourself out of a remote box.
#
#   sudo ./harden-ssh.sh          review the changes, then apply on confirm
#   sudo ./harden-ssh.sh --dry-run  show what would change and exit
#
set -euo pipefail

DROPIN="/etc/ssh/sshd_config.d/99-nimbus-hardening.conf"
DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

c_red() { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn() { printf '\033[32m%s\033[0m\n' "$*"; }
c_ylw() { printf '\033[33m%s\033[0m\n' "$*"; }
die()   { c_red "error: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run as root (sudo ./harden-ssh.sh)"
command -v sshd >/dev/null 2>&1 || die "sshd not found — is OpenSSH server installed?"

#------------------------------------------------------------------
# Lockout guard: refuse to kill password auth with no key in place
#------------------------------------------------------------------
KEY_FOUND=0
KEY_OWNER=""
while IFS=: read -r user _ uid _ _ home _; do
  (( uid >= 1000 )) || continue
  [[ -d "$home" ]] || continue
  ak="$home/.ssh/authorized_keys"
  if [[ -s "$ak" ]] && grep -qE '^\s*(ssh-(rsa|ed25519)|ecdsa-sha2|sk-)' "$ak"; then
    KEY_FOUND=1
    KEY_OWNER="$user"
    echo "found usable public key for: $user ($ak)"
  fi
done < /etc/passwd

if (( ! KEY_FOUND )); then
  c_red "No authorized_keys with a valid public key found for any human user."
  c_red "Disabling password auth now would lock you out of this machine."
  echo
  echo "Fix first, from your laptop:"
  echo "    ssh-keygen -t ed25519 -C \"\$(whoami)@\$(hostname)\""
  echo "    ssh-copy-id <user>@<this-host>"
  echo
  echo "Then re-run this script."
  exit 1
fi

#------------------------------------------------------------------
# The hardened config
#------------------------------------------------------------------
read -r -d '' CONFIG <<'EOF' || true
# Nimbus host SSH hardening. Managed by harden-ssh.sh.

# --- authentication ---
PasswordAuthentication no
PermitEmptyPasswords no
ChallengeResponseAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
PermitRootLogin no
MaxAuthTries 3
LoginGraceTime 20

# --- reduce attack surface ---
X11Forwarding no
AllowAgentForwarding no
AllowTcpForwarding no
PermitTunnel no
UsePAM yes

# --- throttle unauthenticated connections (start:rate:full) ---
MaxStartups 10:30:60
MaxSessions 4

# --- idle timeout: drop dead sessions after ~10 minutes ---
ClientAliveInterval 300
ClientAliveCountMax 2

# --- modern crypto only ---
KexAlgorithms curve25519-sha256,curve25519-sha256@libssh.org,diffie-hellman-group16-sha512
Ciphers chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com
MACs hmac-sha2-512-etm@openssh.com,hmac-sha2-256-etm@openssh.com
HostKeyAlgorithms ssh-ed25519,rsa-sha2-512,rsa-sha2-256
EOF

echo
c_ylw "Proposed $DROPIN:"
echo "-----------------------------------------------------------"
echo "$CONFIG"
echo "-----------------------------------------------------------"
echo

if (( DRY_RUN )); then
  c_grn "Dry run — nothing written."
  exit 0
fi

read -r -p "Apply this and restart sshd? [y/N] " ans
[[ "${ans,,}" == "y" ]] || { echo "aborted"; exit 1; }

#------------------------------------------------------------------
# Apply, validating before restart
#------------------------------------------------------------------
mkdir -p /etc/ssh/sshd_config.d
if [[ -f "$DROPIN" ]]; then
  cp -a "$DROPIN" "${DROPIN}.bak.$(date +%s)"
  echo "backed up existing drop-in"
fi

# Some distros ship an sshd_config that doesn't include the drop-in dir.
if ! grep -qE '^\s*Include\s+/etc/ssh/sshd_config\.d/\*\.conf' /etc/ssh/sshd_config; then
  cp -a /etc/ssh/sshd_config "/etc/ssh/sshd_config.bak.$(date +%s)"
  sed -i '1i Include /etc/ssh/sshd_config.d/*.conf' /etc/ssh/sshd_config
  c_ylw "added missing Include line to /etc/ssh/sshd_config"
fi

umask 022
printf '%s\n' "$CONFIG" > "$DROPIN"
chmod 644 "$DROPIN"

if ! sshd -t; then
  c_red "sshd rejected the config — reverting."
  rm -f "$DROPIN"
  die "no changes applied"
fi
c_grn "config validated (sshd -t)"

if command -v systemctl >/dev/null 2>&1; then
  systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null \
    || systemctl restart ssh 2>/dev/null || systemctl restart sshd
else
  service ssh reload 2>/dev/null || service sshd reload
fi
c_grn "sshd reloaded"

echo
c_ylw "IMPORTANT: keep this session open."
echo "Open a SECOND terminal and confirm you can still get in:"
echo "    ssh ${KEY_OWNER}@<this-host>"
echo
echo "If that fails, revert from this still-open session with:"
echo "    sudo rm $DROPIN && sudo systemctl reload ssh"
echo

#------------------------------------------------------------------
# Brute-force protection
#------------------------------------------------------------------
if ! command -v fail2ban-server >/dev/null 2>&1; then
  c_ylw "fail2ban is not installed. Exposed to the internet, you want it:"
  echo "    sudo apt update && sudo apt install -y fail2ban"
  echo "    sudo tee /etc/fail2ban/jail.d/sshd.local >/dev/null <<'EOF'"
  echo "[sshd]"
  echo "enabled = true"
  echo "maxretry = 4"
  echo "findtime = 10m"
  echo "bantime = 1h"
  echo "EOF"
  echo "    sudo systemctl enable --now fail2ban"
else
  c_grn "fail2ban is installed."
fi
