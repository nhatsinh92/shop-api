# shop-api developer and agent entry points. Every target runs in CI or in the demo.
# Requires: JDK 21 (test/lint/build), Node 22+ (contract lint), Docker (tests, demo).

SHELL := bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

GRADLE  := ./gradlew --console=plain
REDOCLY := npx --yes @redocly/cli@2.82.0
DEMO_KEY := infra/.secrets/jwt-private.pem
# Every compose call gets the same demo signing key, so no command recreates core with another one.
COMPOSE := AUTH_JWT_PRIVATE_KEY="$$(cat $(DEMO_KEY) 2>/dev/null || true)" docker compose -f infra/docker-compose.yml
COMPOSE_PEAK := $(COMPOSE) -f infra/docker-compose.peak.yml --profile peak

.PHONY: help test lint lint-java lint-contract bundle-contract build demo demo-peak demo-down demo-key demo-smoke-auth seed lock test-scripts contract-breaking

help: ## Show targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-16s %s\n", $$1, $$2}'

test: ## Unit + integration tests (Testcontainers; needs Docker), Modulith and ArchUnit checks
	$(GRADLE) test

lint: lint-java lint-contract ## All static checks

lint-java: ## Spotless (google-java-format), Checkstyle, compiler warnings as errors
	$(GRADLE) spotlessCheck checkstyleMain checkstyleTest compileTestJava

lint-contract: ## OpenAPI lint, contract version and convention checks
	node contracts/scripts/check-version.mjs
	node --test contracts/scripts/check-conventions.test.mjs contracts/scripts/check-bundle.test.mjs contracts/scripts/check-bundle-cli.test.mjs
	node contracts/scripts/check-conventions.mjs
	$(REDOCLY) lint
	$(MAKE) bundle-contract

bundle-contract: ## Bundle core and cart into contracts/dist, check self-contained refs and lint the bundles
	rm -rf contracts/dist
	cd contracts && $(REDOCLY) bundle openapi/core.yaml -o dist/core.yaml
	cd contracts && $(REDOCLY) bundle openapi/cart.yaml -o dist/cart.yaml
	node contracts/scripts/check-bundle.mjs contracts/dist
	$(REDOCLY) lint contracts/dist/core.yaml contracts/dist/cart.yaml

build: ## Build the three boot jars (no tests)
	$(GRADLE) bootJar

$(DEMO_KEY):
	mkdir -p $(dir $@)
	umask 077 && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $@

demo-key: $(DEMO_KEY) ## Generate the demo JWT signing key once (RSA 2048, PKCS#8, git-ignored)

demo: $(DEMO_KEY) ## Start the full stack in Docker, wait until healthy, load seed data
	$(COMPOSE) up -d --build --wait postgres redis redpanda mock-payment core cart-service gateway
	$(COMPOSE) run --rm seed
	@echo "gateway      http://localhost:8080/actuator/health"
	@echo "core         http://localhost:8081/actuator/health"
	@echo "cart-service http://localhost:8082/actuator/health"
	@echo "mock payment http://localhost:8090/payments"
	@echo "stop with: make demo-down"

demo-peak: $(DEMO_KEY) ## Demo plus toxiproxy in front of cart-service, a k6 run and Grafana (:3001)
	$(COMPOSE_PEAK) up -d --build --wait \
		postgres redis redpanda mock-payment core cart-service toxiproxy gateway grafana
	$(COMPOSE) run --rm seed
	$(COMPOSE_PEAK) run --rm k6
	@echo "grafana http://localhost:3001  toxiproxy API http://localhost:8474"

demo-down: ## Stop the demo stack (keeps the database volume)
	$(COMPOSE_PEAK) down

seed: $(DEMO_KEY) ## Reload the sample catalog into the running demo database
	$(COMPOSE) run --rm seed

demo-smoke-auth: ## Login, me, refresh, logout through the gateway of a running demo
	bash infra/smoke-auth.sh

lock: ## Refresh gradle.lockfile after changing dependencies
	$(GRADLE) resolveAndLockAll --write-locks

test-scripts: ## Tests for the orchestration scripts (needs jq)
	bash scripts/tests/picker.test.sh
	bash scripts/tests/claim.test.sh
	bash scripts/tests/claim-shared-heartbeat.test.sh
	bash scripts/tests/heartbeat.test.sh
	bash contracts/scripts/check-breaking.test.sh

BASE ?= origin/main

contract-breaking: ## Breaking-change check of the specs against BASE (default origin/main; needs Docker)
	rm -rf build/contract-base
	mkdir -p build/contract-base
	git archive "$(BASE)" contracts | tar -x -C build/contract-base
	bash contracts/scripts/check-breaking.sh build/contract-base/contracts contracts
