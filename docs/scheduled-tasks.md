# Scheduled tasks

The project is built by Claude Code cloud sessions that three scheduled tasks start. The owner
creates them in the Claude app; this file holds their exact prompts and settings. GitHub Actions
never calls a model: it runs CI, `merge-gate` and `contract-publish` only.

## What the cloud environment provides (measured on 2026-11-20)

| Item | Result | Consequence |
| --- | --- | --- |
| Checkouts | `/home/user/shop-api`, `/home/user/shop-web` | Prompts use these paths |
| Java, Node, jq, gh | Java 21, Node 22, jq 1.7, gh 2.89 preinstalled; pnpm 10 (the repo pins 12 through corepack) | `cloud-setup.sh` enables corepack |
| Docker | Not available | Testcontainers tests skip locally (`disabledWithoutDocker`) and run in CI; `make demo` does not run here |
| GitHub GraphQL | Blocked by the session proxy (HTTP 403): `gh pr`, `gh issue` and `gh api graphql` fail | Everything uses REST through `scripts/gh-task.sh` and `gh api repos/...` |
| GitHub REST | Works through the proxy; `gh auth status` still reports an invalid token, which can be ignored | |
| Auto-merge, ready for review | Only through the proxy routes `PUT /repos/{o}/{r}/pulls/{n}/ccr/auto_merge` and `POST .../ccr/ready_for_review` | `gh-task.sh auto-merge` / `ready` |
| Branches | Scheduled tasks push only `claude/`-prefixed branches | Task branches are `claude/issue-<n>-<slug>`, planning branches `claude/plans-phase-<n>` |
| Repository hooks | `.claude/settings.json` hooks are not loaded in multi-repository sessions | No guard hook; the gate is CI (`protected-paths`, `merge-gate`) |

## Cloud environment (shared by the three tasks)

Create it on claude.ai/code: the environment selector under the message box → **New environment**.

| Setting | Value |
| --- | --- |
| Network access | **Limited**, **Allow package managers** checked, plus allowed domains: `services.gradle.org`, `downloads.gradle.org`, `cdn.playwright.dev`, `playwright.download.prss.microsoft.com` |
| Setup script | `bash /home/user/shop-api/scripts/cloud-setup.sh` |
| Environment variables | None. Do not set `GH_TOKEN`: the GitHub proxy authenticates the sessions |
| Repositories (per task) | `nhatsinh92/shop-api` and `nhatsinh92/shop-web` |

### Check before the first scheduled run

Start one session manually in the environment and paste:

```text
Diagnostics only; do not change anything on GitHub. Run and report each command with its output:
tail -30 of the setup script log if you can find it, otherwise: bash /home/user/shop-api/scripts/cloud-setup.sh
cd /home/user/shop-api && bash scripts/next-task.sh; echo "exit $?"
bash scripts/next-review.sh; bash scripts/next-plan.sh
bash scripts/gh-task.sh checks nhatsinh92/shop-api 4
cd /home/user/shop-web && pnpm --version && make lint test
cd /home/user/shop-api && make lint test
```

Every picker must print one JSON line with exit 0 (`{"action":"error"}` means GitHub could not be
read). `make lint test` must pass in both repositories (Testcontainers tests show as skipped).

### Identity

`merge-gate` accepts `human-approved` only when the owner added it directly, not through a GitHub
App (`performed_via_github_app` is empty). Labels that agent sessions add through the Claude GitHub
proxy are expected to carry the app; confirm it the first time a reviewer run adds
`agent-approved` (`gh api repos/<o>/<r>/issues/<pr>/events` shows `performed_via_github_app`). If
it is empty, an agent could also satisfy the owner check, and the owner should stop and ask for a
different approval mechanism before relying on `human-approved`.

## shop-worker

| Setting | Value |
| --- | --- |
| Schedule | Hourly; then edit to Custom `5 * * * *` (minute 5, UTC) |
| Model | Sonnet (default) |
| Repositories / environment | Both repositories / the environment above |

Prompt:

```text
You are shop-worker for the "Mây" shop project. The repositories are at /home/user/shop-api and /home/user/shop-web.

1. Run: cd /home/user/shop-api && bash scripts/next-task.sh
2. If it prints {"action":"none",...} or {"action":"error",...}: reply with its reason in one line and end the session now. Do nothing else.
3. Otherwise read /home/user/shop-api/.claude/prompts/worker.md (and triage.md for "triage") and follow it exactly for that JSON. Read the CLAUDE.md of the repository you work in before changing anything.
4. GitHub access is REST only: use /home/user/shop-api/scripts/gh-task.sh, never gh pr or gh issue. Branches must start with claude/.
5. Everything you write to GitHub and the repositories is in English. Never force push, push to main, delete branches, change repository settings, or add human-approved, approved or infra-change. When the issue is unclear, stop and comment instead of guessing.
6. Finish with a short summary: issue, branch, PR, check status, and anything the owner must do.
```

## shop-reviewer

| Setting | Value |
| --- | --- |
| Schedule | Hourly; then edit to Custom `35 * * * *` |
| Model | Sonnet (default) |
| Repositories / environment | Both repositories / the environment above |

Prompt:

```text
You are shop-reviewer for the "Mây" shop project. The repositories are at /home/user/shop-api and /home/user/shop-web. You review with fresh context: you did not write the code.

1. Run: cd /home/user/shop-api && bash scripts/next-review.sh
2. If it prints {"action":"none",...} or {"action":"error",...}: reply with its reason in one line and end the session now. Do nothing else.
3. Otherwise read /home/user/shop-api/.claude/prompts/reviewer.md and follow it exactly for that PR, including every checklist item.
4. GitHub access is REST only: use /home/user/shop-api/scripts/gh-task.sh, never gh pr or gh issue.
5. Everything you write is in English. Never push commits to the PR branch and never add human-approved.
6. Finish with a one-line verdict and what the owner must do, if anything.
```

## shop-planner

| Setting | Value |
| --- | --- |
| Schedule | Run on demand from the task page (optionally weekly, Monday 08:00) |
| Model | A stronger model than the worker (for example Opus) |
| Repositories / environment | Both repositories / the environment above |

Prompt:

```text
You are shop-planner for the "Mây" shop project. The repositories are at /home/user/shop-api and /home/user/shop-web.

1. Run: cd /home/user/shop-api && bash scripts/next-plan.sh
2. If it prints {"action":"none",...} or {"action":"error",...}: reply with its reason in one line and end the session now. Do nothing else.
3. Otherwise read /home/user/shop-api/.claude/prompts/planner.md and follow it exactly for that JSON. Sources of truth: docs/handoff/shop-build-plan.md, docs/handoff/technical-notes.md, plans/roadmap.md and plans/epics/ in each repository, and design/ in shop-web. Do not change decisions made there.
4. GitHub access is REST only: use /home/user/shop-api/scripts/gh-task.sh, never gh pr or gh issue. Branches must start with claude/.
5. Everything you write is in English. Never label anything approved or human-approved; the owner approves epics.
6. Finish with a summary of what you created and what the owner must review.
```

## What runs where

| Component | Where | Calls a model |
| --- | --- | --- |
| `scripts/next-task.sh`, `next-review.sh`, `next-plan.sh`, `gh-task.sh` | Cloud session | No (bash + gh REST) |
| Implementation, review, planning | Cloud session | Yes |
| `ci`, `e2e`, `merge-gate`, `contract-publish` | GitHub Actions with `GITHUB_TOKEN` | No |
