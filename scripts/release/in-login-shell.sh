#!/usr/bin/env bash
# Runs a command in the runner user's interactive login shell, the environment the manual releases use: the Ruby with
# bundler and fastlane, the Android SDK and JDK, ansible, ssh and gpg-agent (on mini4: zsh -lic, with Homebrew). The
# release workflow (.github/workflows/release.yml, docs/release.md) wraps every build, upload and deploy command in it.
#   scripts/release/in-login-shell.sh bundle exec fastlane android internal
# zsh when installed (mini4), else bash (ryzen); KINFRAME_LOGIN_SHELL picks another. The arguments are passed as they
# are (no re-quoting); the environment, including the job's variables, is inherited.
set -euo pipefail
[[ $# -gt 0 ]] || { echo "usage: in-login-shell.sh <command> [args...]" >&2; exit 2; }
login_shell="${KINFRAME_LOGIN_SHELL:-}"
if [[ -z "$login_shell" ]]; then
  if command -v zsh >/dev/null 2>&1; then login_shell=zsh; else login_shell=bash; fi
fi
exec "$login_shell" -l -i -c 'exec "$@"' kinframe-release "$@"
