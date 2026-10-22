# syntax=docker/dockerfile:1
# Publishable kinframe server image: Go API + media worker + the built web SPA.
# Build from the repo root:  docker build -t kinframe-server .
# (server/Dockerfile is the server-only image used to run the test suite in the runtime toolchain.)

# ---- web SPA ----------------------------------------------------------------
FROM node:22-trixie-slim AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

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
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg libvips-tools ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin kinframe \
 && mkdir -p /data && chown kinframe /data
COPY --from=server /out/kinframe-server /usr/local/bin/kinframe-server
COPY --from=web /web/build /web
USER kinframe
ENV KINFRAME_LISTEN=:8110 KINFRAME_DATA_DIR=/data KINFRAME_WEB_DIR=/web KINFRAME_LOG_FORMAT=json \
    KINFRAME_VERSION=${VERSION}
VOLUME /data
EXPOSE 8110
HEALTHCHECK --interval=30s --timeout=5s CMD ["kinframe-server", "healthcheck"]
ENTRYPOINT ["kinframe-server"]
CMD ["serve"]
