# shop-api — agent guide

Backend of "Mây", a high-traffic e-commerce app for the 12.12 sale. This repository is built mostly
by Claude Code cloud sessions (worker, reviewer, planner) started by scheduled tasks. Read this file,
the CLAUDE.md of the module you touch, and your role prompt in `.claude/prompts/` before working.

## Language

Everything written into this repository is **English**: code, comments, docs, CLAUDE.md files,
issues, PR titles and descriptions, review comments, commit messages and branch names.

## Stack

- Java 21, Spring Boot 4.0, Gradle 9 (Kotlin DSL, version catalog in `gradle/libs.versions.toml`,
  dependency locking in each module's `gradle.lockfile`).
- `gateway`: Spring Cloud Gateway (WebFlux). `core`: Spring Boot + Spring Modulith.
  `cart-service`: Spring Boot + Redis.
- Postgres 18, Redis 8, Redpanda (Kafka API), WireMock as the mock payment gateway.
- Tests: JUnit 5, AssertJ, Spring Boot test slices, Testcontainers 2, ArchUnit, Spring Modulith.
- Contract: OpenAPI 3.1 in `contracts/openapi/`, linted with Redocly, released as the npm package
  `@shop/api-contract` (a GitHub Release tarball, see `contracts/CLAUDE.md`).

## Commands

| Command | What it does |
| --- | --- |
| `make test` | All tests, including Testcontainers (needs Docker), Modulith verification and ArchUnit |
| `make lint` | Spotless (google-java-format), Checkstyle, `-Werror` compile, OpenAPI lint, contract version check |
| `make build` | Boot jars for the three apps |
| `make demo` | Full stack in Docker, waits until healthy, loads seed data (gateway on :8080) |
| `make demo-peak` | Demo plus toxiproxy in front of cart-service, a k6 run and Grafana (:3001) |
| `make demo-down` | Stop the stack |
| `make lock` | Refresh `gradle.lockfile` after changing dependencies (commit the result) |
| `make test-scripts` | Fixture tests for the picker scripts (needs jq) |
| `./gradlew spotlessApply` | Format Java and Gradle files before committing |

Run `make lint test` before every push.

Claude Code cloud sessions have **no Docker**: Testcontainers tests skip themselves there
(`@Testcontainers(disabledWithoutDocker = true)`) and run in CI, where a skipped test fails the
build. Push, then wait for CI with `bash scripts/gh-task.sh checks-wait <repo> <pr>` and read
failures with `bash scripts/gh-task.sh failed-logs <repo> <pr>`. New Testcontainers tests use the
same annotation; never skip or disable a test any other way.

GitHub access in cloud sessions is **REST only** (GraphQL, and therefore `gh pr` / `gh issue`, is
blocked by the session proxy). Use `scripts/gh-task.sh` for issues, labels, comments, PRs, checks
and auto-merge, or `gh api repos/...` directly.

## Repository map

```
contracts/openapi/   core.yaml, cart.yaml, errors.yaml (source of truth for every API)
gateway/             edge: routes, JWT from cookie (Phase 2), rate limits
core/                modular monolith: auth, product, order, checkout
cart-service/        Redis cart, hot path at peak
seed/                sample catalog for the demo
infra/               docker-compose.yml (+ peak overlay), app.Dockerfile, mock payment, k6, Grafana
plans/               roadmap.md and epics/ (approved by the owner)
scripts/             next-task.sh, next-review.sh, next-plan.sh, heartbeat.sh, gh-task.sh, cloud-setup.sh
                     (orchestration, no model calls; see docs/scheduled-tasks.md)
.claude/             role prompts and settings for agent sessions
.github/             CI, merge-gate, contract-publish workflows and policy scripts
```

## Boundaries

- `core` modules (`auth`, `product`, `order`, `checkout`) talk only through each module's public API
  (types in the module's root package) and Spring application events. Sub-packages are internal.
  `ModularityTests` fails the build on violations.
- `cart-service` never reads the core database. It calls `core` for prices and listens to
  `product.updated` on Redpanda.
- The three deployables share no code at compile time. Shared shapes come from the contract.
- Every endpoint implements the contract exactly and is covered by a contract test that validates
  responses against the spec.
- Contract changes are their own task and run first. If your task needs a contract change that is
  not there yet, stop and open a proposal issue labelled `area:contract`; do not edit the spec.

## What may change and what must not

Each issue's **Scope** section is binding: change only paths under "May change". The reviewer
rejects PRs that leave the scope.

Protected paths. A PR that touches them fails the `protected-paths` check unless the issue and PR
carry `infra-change` (added only by the owner or the planner), and `merge-gate` also requires the
owner's `human-approved` label:

- `.github/**`, `.claude/**`
- Lint and coverage config: root `build.gradle.kts`, `config/**`, `redocly.yaml`,
  `.redocly.lint-ignore.yaml`, `gradle.properties`, `gradle/wrapper/**`
- Secrets-scan config: `.gitleaks.toml` (the UUID test-data allowlist)
- Existing test files (`**/src/test/**` that already exist on `main`): add new tests, never edit,
  weaken or delete existing ones in a feature task
- Contract major version bumps (`merge-gate` needs `human-approved`, no `infra-change`)

Security-sensitive code (`gateway/**`, `core/src/main/java/com/mayshop/core/auth/**`, any
`*Security*` class or `security/` package) is **not** protected (owner decision 2026-11-22): it merges on
`agent-approved` and green CI, and the reviewer applies the security checklist in its prompt.

Never add `@Disabled`, `@SuppressWarnings`, `// CHECKSTYLE:OFF`, `spotless:off` or lint ignore
entries to make a check pass. Fix the code. Never change tests or fixtures so that they pass; if a
test is wrong, say so in the PR and leave it for the owner.

## How to read an issue

Issues follow `.github/ISSUE_TEMPLATE/task.md`:

1. **Labels / Depends on / Epic**: `area:*`, `phase:*`, `size:*`; dependencies must be closed.
2. **Context**: the contract section, the epic section in `plans/epics/`, design links. Read them.
3. **Goal**: one sentence of outcome.
4. **Scope**: "May change" / "Must not change". Binding.
5. **Acceptance criteria**: each one needs a test or a command that proves it. Name tests after the
   criterion so the reviewer can map them.
6. **Verification**: commands that must pass (always at least `make lint test`). New-code coverage
   at least 80%.
7. **Out of scope**: do not do these, even if easy.

## When to stop and leave a comment

Stop, comment on the issue (what is unclear, the options you see, what you need) and end the run
instead of guessing when:

- an acceptance criterion is ambiguous, contradicts the contract or another criterion, or cannot be
  tested;
- the task needs a change outside its scope, to a protected path, or to the contract;
- a dependency is missing or a required service or tool is unavailable;
- the same check fails three times for a reason you do not understand;
- the plan, an epic and the code disagree.

Label the issue `blocked` when you cannot continue at all.

## Conventions

- Branch: `claude/issue-<number>-<short-slug>` (cloud sessions may push only `claude/` branches).
  One issue per branch and PR. PR body has `Closes #<n>`.
- Commits: Conventional Commits (`feat(cart): ...`, `fix(core): ...`, `test: ...`, `chore: ...`),
  small, one step each, pushed after each step.
- Packages: `com.mayshop.<deployable>[.<module>]`. Constructor injection only. SLF4J logging.
- Errors: RFC 9457 problem details with a stable `code` (`errors.yaml`).
- Configuration via environment variables with local defaults in `application.yml`; no secrets in
  the repository.
