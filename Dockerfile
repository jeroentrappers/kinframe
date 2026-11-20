# syntax=docker/dockerfile:1
# Publishable kinframe server image: Go API + media worker + the built web SPA.
# Build from the repo root:  docker build -t kinframe-server .
# (server/Dockerfile is the server-only image used to run the test suite in the runtime toolchain.)

# ---- web SPA ----------------------------------------------------------------
# No Rust needed: the photo preparation WebAssembly module (web/prep-wasm) is committed, built, in
# web/src/lib/prep/wasm, and the HEIC decoder comes prebuilt from the pinned libheif-js npm package. CI checks that
# the committed module matches its source (ci.yml, "Web prep WebAssembly").
FROM node:22-trixie-slim AS web
ARG VERSION=0.1.0-dev
ARG REVISION=
# Crash reporting (docs/spec/crash-reporting.md): source maps go to GlitchTip when the build gets the secret
# `sentry_auth_token` (docker build --secret id=sentry_auth_token,env=SENTRY_AUTH_TOKEN); without it the upload is
# skipped. They are never part of the image.
ARG SENTRY_URL=https://errors.kinframe.appmire.be
ARG SENTRY_ORG=kinframe
ARG SENTRY_PROJECT=kinframe-web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN --mount=type=secret,id=sentry_auth_token,env=SENTRY_AUTH_TOKEN \
    rev=$(printf %.7s "$REVISION") \
 && export KINFRAME_WEB_RELEASE="kinframe-web@${VERSION}${rev:++$rev}" \
 && npm run build \
 && sh scripts/upload-sourcemaps.sh

# ---- server -----------------------------------------------------------------
FROM golang:1.27-trixie AS server
WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/kinframe-server ./cmd/kinframe-server

# ---- runtime ----------------------------------------------------------------
FROM debian:trixie-slim
ARG VERSION=0.1.0-dev
ARG REVISION=
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg libvips-tools ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin kinframe \
 && mkdir -p /data && chown kinframe /data
COPY --from=server /out/kinframe-server /usr/local/bin/kinframe-server
COPY --from=web /web/build /web
USER kinframe
ENV KINFRAME_LISTEN=:8110 KINFRAME_DATA_DIR=/data KINFRAME_WEB_DIR=/web KINFRAME_LOG_FORMAT=json \
    KINFRAME_VERSION=${VERSION} KINFRAME_REVISION=${REVISION}
VOLUME /data
EXPOSE 8110
HEALTHCHECK --interval=30s --timeout=5s CMD ["kinframe-server", "healthcheck"]
ENTRYPOINT ["kinframe-server"]
CMD ["serve"]
