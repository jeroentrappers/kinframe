# kinframe server. Run `make help` for targets.
GO        ?= go
BIN       ?= bin/kinframe-server
IMAGE     ?= kinframe-server:dev
SPEC      := ../docs/api/openapi.yaml
GEN_DIR   := internal/api/gen
STATICCHECK := honnef.co/go/tools/cmd/staticcheck@v0.8.2
COMPOSE   := docker compose -f ../deploy/compose/docker-compose.dev.yml

# Dev defaults (dev auth, local compose Postgres). Override on the command line.
DEV_ENV := KINFRAME_DEV_AUTH=1 \
	KINFRAME_DATABASE_URL=$${KINFRAME_DATABASE_URL:-postgres://kinframe:kinframe-dev@127.0.0.1:5433/kinframe?sslmode=disable} \
	KINFRAME_DATA_DIR=$${KINFRAME_DATA_DIR:-./data} \
	KINFRAME_LOG_LEVEL=$${KINFRAME_LOG_LEVEL:-debug}

.PHONY: help generate build test test-short test-docker lint run db-up db-down docker clean

help:
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

generate: ## Regenerate API code from docs/api/openapi.yaml
	$(GO) run ./tools/specprep $(SPEC) $(GEN_DIR)/openapi.prepared.yaml
	$(GO) tool oapi-codegen -config $(GEN_DIR)/cfg.yaml $(GEN_DIR)/openapi.prepared.yaml
	$(GO) run ./tools/respgen $(GEN_DIR)/api.gen.go internal/api/response.gen.go

build: ## Build the server binary into bin/
	CGO_ENABLED=0 $(GO) build -trimpath -o $(BIN) ./cmd/kinframe-server

test: ## Run all tests (embedded PostgreSQL; media tests skip without vips/ffmpeg)
	$(GO) test -race ./...

test-docker: ## Run all tests inside the runtime image's toolchain (vips + ffmpeg required)
	docker build --target test -t kinframe-server:test .

lint: ## go vet + staticcheck
	$(GO) vet ./...
	$(GO) run $(STATICCHECK) $$($(GO) list ./... | grep -v /api/gen)

db-up: ## Start the dev Postgres (and Logto with PROFILE=logto)
	$(COMPOSE) $(if $(PROFILE),--profile $(PROFILE)) up -d

db-down: ## Stop the dev stack
	$(COMPOSE) --profile logto --profile server down

run: build ## Run the server locally in dev auth mode against the dev Postgres
	$(DEV_ENV) ./$(BIN)

docker: ## Build the runtime image
	docker build -t $(IMAGE) .

clean:
	rm -rf bin
