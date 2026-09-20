#!/bin/bash
# Bootstrap for the single-box beta (FZ-152).
#
# Deliberately thin. It installs a container runtime and nothing else: what runs on this
# box is FZ-153's compose stack, delivered by FZ-154's deploy rather than baked in here.
# Bootstrap that also deploys is bootstrap nobody can re-run.
set -euo pipefail

dnf update -y

# Docker, and Compose v2 as a plugin rather than the standalone binary -- `docker compose`
# is what every command in the runbook assumes.
dnf install -y docker
mkdir -p /usr/libexec/docker/cli-plugins
ARCH="$(uname -m)"
curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-${ARCH}" \
  -o /usr/libexec/docker/cli-plugins/docker-compose
chmod +x /usr/libexec/docker/cli-plugins/docker-compose

systemctl enable --now docker

# Unattended security updates. A box nobody patches is the cost of not having a managed
# service, and it is the one part of that cost that can be automated away.
dnf install -y dnf-automatic
sed -i 's/^upgrade_type.*/upgrade_type = security/' /etc/dnf/automatic.conf
sed -i 's/^apply_updates.*/apply_updates = yes/' /etc/dnf/automatic.conf
systemctl enable --now dnf-automatic.timer

# Where the stack lives. FZ-154 writes compose.yaml here and runs from it.
install -d -m 0755 /opt/freezehub
install -d -m 0700 /opt/freezehub/secrets

# The SSM agent ships enabled on AL2023; make the dependency explicit rather than assumed,
# because losing it means losing every route onto this box (there is no SSH).
systemctl enable --now amazon-ssm-agent

echo "bootstrap complete" > /opt/freezehub/.bootstrapped
