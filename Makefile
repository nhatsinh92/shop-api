# shop-api developer and agent entry points. Every target runs in CI or in the demo.
# Requires: JDK 21 (test/lint/build), Node 22+ (contract lint), Docker (tests, demo).

SHELL := bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

GRADLE  := ./gradlew --console=plain
REDOCLY := npx --yes @redocly/cli@2.82.0
COMPOSE := docker compose -f infra/docker-compose.yml
COMPOSE_PEAK := $(COMPOSE) -f infra/docker-compose.peak.yml --profile peak

.PHONY: help test lint lint-java lint-contract build demo demo-peak demo-down seed lock test-scripts

help: ## Show targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

test: ## Unit + integration tests (Testcontainers; needs Docker), Modulith and ArchUnit checks
	$(GRADLE) test

lint: lint-java lint-contract ## All static checks

lint-java: ## Spotless (google-java-format), Checkstyle, compiler warnings as errors
	$(GRADLE) spotlessCheck checkstyleMain checkstyleTest compileTestJava

lint-contract: ## OpenAPI lint, contract version and convention checks
	node contracts/scripts/check-version.mjs
	node --test contracts/scripts/check-conventions.test.mjs
	node contracts/scripts/check-conventions.mjs
	$(REDOCLY) lint

build: ## Build the three boot jars (no tests)
	$(GRADLE) bootJar

demo: ## Start the full stack in Docker, wait until healthy, load seed data
	$(COMPOSE) up -d --build --wait postgres redis redpanda mock-payment core cart-service gateway
	$(COMPOSE) run --rm seed
	@echo "gateway      http://localhost:8080/actuator/health"
	@echo "core         http://localhost:8081/actuator/health"
	@echo "cart-service http://localhost:8082/actuator/health"
	@echo "mock payment http://localhost:8090/payments"
	@echo "stop with: make demo-down"

demo-peak: ## Demo plus toxiproxy in front of cart-service, a k6 run and Grafana (:3001)
	$(COMPOSE_PEAK) up -d --build --wait \
		postgres redis redpanda mock-payment core cart-service toxiproxy gateway grafana
	$(COMPOSE) run --rm seed
	$(COMPOSE_PEAK) run --rm k6
	@echo "grafana http://localhost:3001  toxiproxy API http://localhost:8474"

demo-down: ## Stop the demo stack (keeps the database volume)
	$(COMPOSE_PEAK) down

seed: ## Reload the sample catalog into the running demo database
	$(COMPOSE) run --rm seed

lock: ## Refresh gradle.lockfile after changing dependencies
	$(GRADLE) resolveAndLockAll --write-locks

test-scripts: ## Tests for the orchestration scripts (needs jq)
	bash scripts/tests/picker.test.sh
