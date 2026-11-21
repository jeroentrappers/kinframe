#!/usr/bin/env bash
# Production deploy of a kinframe server image to appmire-hetz1, from the private kinframe-deploy checkout on the
# release workflow (ryzen) or by hand (mini4, the laptop). The vault passphrase is kinframe-deploy's .vault-pass
# (ansible.cfg vault_password_file). The workflow builds all of it from secrets in $RUNNER_TEMP:
#   KINFRAME_DEPLOY_DIR   the kinframe-deploy clone (default ~/Developer/gitlab/jeroentrappers/kinframe-deploy)
#   KINFRAME_SSH_CONFIG   an ssh config with Host appmire-hetz1 (default: ~/.ssh/config); ansible gets it through
#                         ANSIBLE_SSH_ARGS, git through GIT_SSH_COMMAND
#   GIT_CONFIG_*          gpg.program and user.signingkey for the signed commit
#   scripts/release/deploy-server.sh 0.7.4          # the image of tag v0.7.4 (ci.yml publishes it as 0.7.4)
#   scripts/release/deploy-server.sh sha-0725809    # the image of a commit
# 1. pg_dump of database kinframe into ~/kinframe-backups on the host, checked to be over 100 KB
# 2. kinframe_version in group_vars/all/main.yml
# 3. scripts/fetch-role.sh main
# 4. ansible-playbook site.yml --tags app
# 5. https://kinframe.appmire.be/healthz answers "ok" and kinframe-server runs the new image
# 6. commit the bump (gpg-signed, "kinframe <version>") and push
set -euo pipefail

version="${1:?usage: deploy-server.sh <image tag: X.Y.Z or sha-xxxxxxx>}"
deploy_dir="${KINFRAME_DEPLOY_DIR:-$HOME/Developer/gitlab/jeroentrappers/kinframe-deploy}"
host="${KINFRAME_DEPLOY_HOST:-appmire-hetz1}"
health_url="${KINFRAME_HEALTH_URL:-https://kinframe.appmire.be/healthz}"
role_ref="${KINFRAME_ROLE_REF:-main}"
vars=group_vars/all/main.yml
author_name="Jeroen Trappers"
author_email="jeroen.trappers@gmail.com"

fail() { echo "::error::$*"; exit 1; }
remote() {
  if [[ -n "${KINFRAME_SSH_CONFIG:-}" ]]; then ssh -F "$KINFRAME_SSH_CONFIG" -n -o BatchMode=yes "$host" "$@"
  else ssh -n -o BatchMode=yes "$host" "$@"; fi
}

[[ "$version" =~ ^([0-9]+\.[0-9]+\.[0-9]+|sha-[0-9a-f]{7})$ ]] || fail "image tag $version is not X.Y.Z or sha-xxxxxxx"
[[ -d "$deploy_dir/.git" ]] || fail "no kinframe-deploy checkout at $deploy_dir"
cd "$deploy_dir"
[[ -f .vault-pass ]] || fail "$deploy_dir/.vault-pass is missing (the vault passphrase, ansible.cfg vault_password_file)"

# The version bump must be the only change this deploy commits.
[[ -z "$(git status --porcelain --untracked-files=no)" ]] \
  || fail "kinframe-deploy has uncommitted changes; commit or revert them first (git -C $deploy_dir status)"
git pull --ff-only --quiet

echo "::group::1. Database backup on $host"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup="kinframe-$stamp-pre-$version.sql.gz"
remote "bash -o pipefail -c 'mkdir -p ~/kinframe-backups && sudo -n docker exec kinframe-postgres pg_dump -U postgres kinframe | gzip > ~/kinframe-backups/$backup'"
size="$(remote "stat -c %s ~/kinframe-backups/$backup")"
[[ "$size" =~ ^[0-9]+$ ]] || fail "could not read the size of ~/kinframe-backups/$backup"
(( size > 102400 )) || fail "backup ~/kinframe-backups/$backup is only $size bytes (expected over 100 KB)"
echo "$host:kinframe-backups/$backup: $size bytes"
echo "::endgroup::"

echo "::group::2. kinframe_version: \"$version\""
grep -q '^kinframe_version:' "$vars" || fail "$vars has no kinframe_version line"
KINFRAME_VERSION="$version" perl -pi -e 's/^(kinframe_version:\s*)"[^"]*"/$1"$ENV{KINFRAME_VERSION}"/' "$vars"
grep -q "^kinframe_version: \"$version\"" "$vars" || fail "could not set kinframe_version in $vars"
git --no-pager diff -- "$vars"
echo "::endgroup::"

echo "::group::3. Role @ $role_ref"
scripts/fetch-role.sh "$role_ref"
echo "::endgroup::"

echo "::group::4. ansible-playbook site.yml --tags app"
ansible-playbook site.yml --tags app
echo "::endgroup::"

echo "::group::5. Health"
healthy=""
for _ in $(seq 1 30); do
  if [[ "$(curl -fsS --max-time 10 "$health_url" 2>/dev/null || true)" == ok* ]]; then healthy=1; break; fi
  sleep 10
done
[[ -n "$healthy" ]] || fail "$health_url did not answer ok within 5 minutes"
image="$(remote "sudo -n docker inspect -f '{{.Config.Image}}' kinframe-server")"
[[ "$image" == *":$version" ]] || fail "kinframe-server runs $image, not :$version"
echo "$health_url ok, kinframe-server runs $image"
echo "::endgroup::"

echo "::group::6. Commit and push"
if git diff --quiet -- "$vars"; then
  echo "kinframe_version was already $version: nothing to commit"
else
  git -c user.name="$author_name" -c user.email="$author_email" commit -S --quiet -m "kinframe $version" -- "$vars"
  git push --quiet
  git --no-pager log -1 --format='%h %s (%G?)'
fi
echo "::endgroup::"
