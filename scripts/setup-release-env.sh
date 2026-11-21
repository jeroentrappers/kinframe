#!/usr/bin/env bash
# Creates (or updates) the GitHub environment `production` that gates .github/workflows/release.yml (docs/release.md):
# a required reviewer (default jeroentrappers; you may approve your own releases), and deployments only from v* tags
# and main (workflow_dispatch runs on main). Also asks approval for workflows from all outside contributors' pull
# requests. Run it yourself, once, with gh signed in as a repository admin:
#   scripts/setup-release-env.sh [reviewer-login]
# Then set the secrets: scripts/release-secrets.sh (docs/release.md#secrets).
set -euo pipefail
repo="${KINFRAME_REPO:-jeroentrappers/kinframe}"
reviewer="${1:-jeroentrappers}"
env_name=production

reviewer_id="$(gh api "users/$reviewer" --jq .id)"
gh api -X PUT "repos/$repo/environments/$env_name" --input - >/dev/null <<JSON
{
  "wait_timer": 0,
  "prevent_self_review": false,
  "reviewers": [{ "type": "User", "id": $reviewer_id }],
  "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true }
}
JSON
echo "environment $env_name: reviewer $reviewer"

existing="$(gh api "repos/$repo/environments/$env_name/deployment-branch-policies" --jq '.branch_policies[] | "\(.type):\(.name)"')"
for policy in tag:v* branch:main; do
  type="${policy%%:*}"; name="${policy#*:}"
  if grep -qx "$type:$name" <<< "$existing"; then
    echo "deployments from $type $name: already allowed"
  else
    gh api -X POST "repos/$repo/environments/$env_name/deployment-branch-policies" -f name="$name" -f type="$type" >/dev/null
    echo "deployments from $type $name: allowed"
  fi
done

# Pull requests from forks run workflows only after approval (never on the release runners: no workflow sends a
# pull_request job there, docs/release.md#security).
if gh api -X PUT "repos/$repo/actions/permissions/fork-pr-contributor-approval" \
     -f approval_policy=all_external_contributors >/dev/null 2>&1; then
  echo "fork pull request workflows: approval required for all outside contributors"
else
  echo "Set by hand: Settings → Actions → General → Approval for running fork pull request workflows → all outside collaborators"
fi

# The frame app's Play releases stay drafts until Google has reviewed the app; then: gh variable set ... completed.
if ! gh variable list --repo "$repo" | grep -q '^FRAME_PLAY_RELEASE_STATUS'; then
  gh variable set FRAME_PLAY_RELEASE_STATUS --repo "$repo" --body draft
  echo "variable FRAME_PLAY_RELEASE_STATUS=draft"
fi
