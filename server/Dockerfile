# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM golang:1.27-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/kinframe-server ./cmd/kinframe-server

# ---- test: full suite with the same media tools as production ----------------
FROM build AS test
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg libvips-tools \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --create-home tester \
 && chown -R tester /src
# Embedded PostgreSQL refuses to run as root.
USER tester
ENV KINFRAME_REQUIRE_MEDIA_TOOLS=1 GOFLAGS=-buildvcs=false
RUN go test -count=1 ./...

# ---- runtime --------------------------------------------------------------
FROM debian:bookworm-slim AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg libvips-tools ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin kinframe \
 && mkdir -p /data /web && chown kinframe /data
COPY --from=build /out/kinframe-server /usr/local/bin/kinframe-server
USER kinframe
ENV KINFRAME_LISTEN=:8110 KINFRAME_DATA_DIR=/data KINFRAME_LOG_FORMAT=json
VOLUME /data
EXPOSE 8110
HEALTHCHECK --interval=30s --timeout=5s CMD ["kinframe-server", "healthcheck"]
ENTRYPOINT ["kinframe-server"]
CMD ["serve"]
