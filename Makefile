# shop-api developer and agent entry points. Every target runs in CI or in the demo.
# Requires: JDK 21 (test/lint/build), Node 22+ (contract lint), Docker (tests, demo).

SHELL := bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

GRADLE  := ./gradlew --console=plain
REDOCLY := npx --yes @redocly/cli@2.82.0
DEMO_KEY := infra/.secrets/jwt-private.pem
REVALIDATE_SECRET_FILE := infra/.secrets/revalidate-secret
INTERNAL_TOKEN_FILE := infra/.secrets/internal-api-token
DEMO_SECRETS := $(DEMO_KEY) $(REVALIDATE_SECRET_FILE) $(INTERNAL_TOKEN_FILE)
# Every compose call gets the same demo secrets, so no command recreates core with other ones.
COMPOSE := AUTH_JWT_PRIVATE_KEY="$$(cat $(DEMO_KEY) 2>/dev/null || true)" \
	REVALIDATE_SECRET="$$(cat $(REVALIDATE_SECRET_FILE) 2>/dev/null || true)" \
	INTERNAL_API_TOKEN="$$(cat $(INTERNAL_TOKEN_FILE) 2>/dev/null || true)" \
	docker compose -f infra/docker-compose.yml
COMPOSE_PEAK := $(COMPOSE) -f infra/docker-compose.peak.yml --profile peak

.PHONY: flag cart-restore help test lint lint-java lint-contract bundle-contract build demo demo-peak demo-down demo-key demo-smoke-auth demo-smoke-product demo-smoke-cart demo-smoke-checkout demo-k6-cart seed lock test-scripts contract-breaking

help: ## Show targets
	@grep -E '^[a-z0-9-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-16s %s\n", $$1, $$2}'

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

$(REVALIDATE_SECRET_FILE) $(INTERNAL_TOKEN_FILE):
	mkdir -p $(dir $@)
	umask 077 && openssl rand -hex 32 >$@

demo-key: $(DEMO_SECRETS) ## Generate the demo secrets once: JWT key, revalidate secret, internal token

demo: $(DEMO_SECRETS) ## Start the full stack in Docker, wait until healthy, load seed data
	$(COMPOSE) up -d --build --wait postgres redis redpanda mock-payment core cart-service gateway
	$(COMPOSE) run --rm seed
	@echo "gateway      http://localhost:8080/actuator/health"
	@echo "core         http://localhost:8081/actuator/health"
	@echo "cart-service http://localhost:8082/actuator/health"
	@echo "mock payment http://localhost:8090/payments"
	@echo "stop with: make demo-down"

demo-peak: $(DEMO_SECRETS) ## Demo plus toxiproxy in front of cart-service, a k6 run and Grafana (:3001)
	$(COMPOSE_PEAK) up -d --build --wait \
		postgres redis redpanda mock-payment core cart-service toxiproxy gateway prometheus grafana
	$(COMPOSE) run --rm seed
	$(COMPOSE_PEAK) run --rm k6
	@echo "grafana http://localhost:3001 (dashboard: Mây peak)  prometheus http://localhost:9090  toxiproxy API http://localhost:8474"

flag: ## Switch a feature flag in the demo Redis: make flag NAME=online-payment VALUE=off (a Redis loss resets flags to their defaults)
	@case "$(NAME)" in ''|*[!a-z0-9-]*|-*|*-) echo "NAME must be a kebab-case flag, e.g. recommendations"; exit 2;; esac
	@case "$(VALUE)" in on|off) ;; *) echo "VALUE must be on or off"; exit 2;; esac
	$(COMPOSE) exec -T redis redis-cli HSET flags $(NAME) $(VALUE)

demo-down: ## Stop the demo stack (keeps the database volume)
	$(COMPOSE_PEAK) down

cart-restore: $(DEMO_SECRETS) ## Rebuild lost Redis carts from cart_snapshot (one-off cart-service container; the demo stack must run)
	$(COMPOSE) run --rm --no-deps cart-service --cart.restore=true

seed: $(DEMO_SECRETS) ## Reload the sample catalog into the running demo database
	$(COMPOSE) run --rm seed

demo-smoke-auth: ## Login, me, refresh, logout through the gateway of a running demo
	bash infra/smoke-auth.sh

demo-smoke-product: ## List, detail, availability, internal change and product.updated event of a running demo
	bash infra/smoke-product.sh

demo-smoke-cart: ## Guest and signed-in cart operations, merge and cart snapshot of a running demo
	bash infra/smoke-cart.sh

demo-smoke-checkout: ## Reserve, COD order, idempotent replay, failed and timed-out payment of a running demo
	bash infra/smoke-checkout.sh

# MSYS_NO_PATHCONV=1 stops Git Bash on Windows from rewriting /scripts/cart.js to a host path.
demo-k6-cart: ## k6 cart load (50 VUs, 60 s) through the gateway of a running demo; fails at p95 >= 50 ms or errors >= 1 %
	DEMO_USERS="$$(grep -Eo '`[a-z]+@example\.com` \| `[a-z]+-[a-z]+-[0-9]{4}`' seed/README.md | sed -e 's/`//g' -e 's/ | /:/' | paste -sd, -)" \
	MSYS_NO_PATHCONV=1 $(COMPOSE) --profile peak run --rm --no-deps -e DEMO_USERS k6 run /scripts/cart.js

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
