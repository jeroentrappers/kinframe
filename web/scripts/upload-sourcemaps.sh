#!/bin/sh
# Uploads the web build's source maps to the crash reporting server (docs/spec/crash-reporting.md §5), then deletes
# them from build/ so they are never served. Without SENTRY_AUTH_TOKEN the upload is skipped with a warning, and a
# failed upload only warns: the build goes on. The maps are deleted either way.
#
#   KINFRAME_WEB_RELEASE  the release the build was made with (vite.config.ts), e.g. kinframe-web@0.3.0+1a2b3c4
#   SENTRY_AUTH_TOKEN     GlitchTip/Sentry auth token; empty = skip the upload
#   SENTRY_URL            default https://errors.kinframe.appmire.be
#   SENTRY_ORG            default kinframe
#   SENTRY_PROJECT        default kinframe-web
set -eu
cd "$(dirname "$0")/.."
dir=build
release=${KINFRAME_WEB_RELEASE:-kinframe-web@$(node -p "require('./package.json').version")}

if [ -z "${SENTRY_AUTH_TOKEN:-}" ]; then
	echo "warning: SENTRY_AUTH_TOKEN not set, source maps for $release not uploaded" >&2
else
	export SENTRY_URL="${SENTRY_URL:-https://errors.kinframe.appmire.be}"
	export SENTRY_ORG="${SENTRY_ORG:-kinframe}"
	export SENTRY_PROJECT="${SENTRY_PROJECT:-kinframe-web}"
	cli="npx --yes @sentry/cli@3.8.2"
	# Debug ids tie each minified file to its map, whatever URL the file is loaded from.
	if $cli sourcemaps inject "$dir" && $cli sourcemaps upload --release "$release" "$dir"; then
		echo "source maps uploaded for $release"
	else
		echo "warning: source map upload for $release failed; the build goes on" >&2
	fi
fi
find "$dir" -name '*.map' -type f -delete
