# Scheduled tasks

The project is built by Claude Code cloud sessions that three scheduled tasks start. The owner
creates them in the Claude app; this file holds their exact prompts and settings. GitHub Actions
never calls a model: it runs CI, `merge-gate` and `contract-publish` only.

## Cloud environment (shared by the three tasks)

| Setting | Value |
| --- | --- |
| Repositories | `nhatsinh92/shop-api` and `nhatsinh92/shop-web`, both attached with push access through the Claude GitHub connection |
| Setup script | `bash shop-api/scripts/cloud-setup.sh` (installs JDK 21, Node 22, pnpm, jq, gh, Playwright Chromium; warms Gradle and pnpm caches; prints whether Docker is available) |
| Network | GitHub, Maven Central, Gradle plugin portal and services.gradle.org, the npm registry, Docker Hub and ghcr.io (if Docker is available), cdn.playwright.dev, deb.nodesource.com, packages.adoptium.net, cli.github.com |
| Secrets | None in the repositories. The sessions use the Claude GitHub connection. |
| Permissions | Automatic approval for the tasks; `.claude/settings.json` and the guard hook in each repository deny force pushes, branch deletion, settings changes, owner-only labels and protected-path edits. |

### Checks before the first scheduled run

Run one manual session in the environment and confirm (paste the output into the bootstrap PR):

1. `bash shop-api/scripts/cloud-setup.sh` ends with a `docker:` line. If Docker is not available,
   Testcontainers and e2e tests run only in CI, and the worker relies on `gh pr checks`.
2. `gh auth status` succeeds and `gh issue list --repo nhatsinh92/shop-web` works. The pickers,
   heartbeats and PR commands need `gh`. If the GitHub connection does not authenticate `gh`,
   decide how to provide a token to the environment (see "Identity" below) before scheduling.
3. `cd shop-api && bash scripts/next-task.sh` prints `{"action":"none",...}` within seconds.

### Identity

`merge-gate` accepts `human-approved` only when the owner added it directly, not through a GitHub
App (`performed_via_github_app` is empty). Labels added through the Claude GitHub connection carry
the app and are rejected. If `gh` in the sessions authenticates with a personal token of the owner
instead, agent label changes look exactly like the owner's: then the guard hook is the only thing
stopping an agent from adding `human-approved`. In that case prefer a token of a separate machine
account with write access to both repositories, so the owner check stays meaningful.

## shop-worker

| Setting | Value |
| --- | --- |
| Schedule | Hourly at :05 (`5 * * * *`) |
| Model | Sonnet (default) |
| Notifications | On: PR opened, issue blocked or triaged, PR waiting for `human-approved` |

Prompt:

```text
You are shop-worker for the "Mây" shop project. The repositories shop-api and shop-web are attached.

1. Run: cd shop-api && bash scripts/next-task.sh
2. If it prints {"action":"none",...}: reply with its reason in one line and end the session now. Do nothing else.
3. Otherwise read shop-api/.claude/prompts/worker.md (and shop-api/.claude/prompts/triage.md for "triage") and follow it exactly for that JSON. Read the CLAUDE.md of the repository you work in before changing anything.
4. Name this session "worker · <repo> #<number> <short title>".
5. Everything you write to GitHub and the repositories is in English. Never force push, push to main, delete branches, change repository settings, or add human-approved, approved or infra-change. When the issue is unclear, stop and comment instead of guessing.
6. Finish with a short summary: issue, branch, PR, check status, and anything the owner must do. Send a notification if you opened a PR, blocked or triaged an issue, or a PR now waits for the owner's human-approved label.
```

## shop-reviewer

| Setting | Value |
| --- | --- |
| Schedule | Hourly at :35 (`35 * * * *`) |
| Model | Sonnet (default) |
| Notifications | On: changes requested, PR waiting for `human-approved`, PR blocked |

Prompt:

```text
You are shop-reviewer for the "Mây" shop project. The repositories shop-api and shop-web are attached. You review with fresh context: you did not write the code.

1. Run: cd shop-api && bash scripts/next-review.sh
2. If it prints {"action":"none",...}: reply with its reason in one line and end the session now. Do nothing else.
3. Otherwise read shop-api/.claude/prompts/reviewer.md and follow it exactly for that PR, including every checklist item.
4. Name this session "reviewer · <repo> #<pr> <short title>".
5. Everything you write is in English. Never push commits to the PR branch and never add human-approved.
6. Finish with a one-line verdict. Send a notification if you requested changes, blocked the PR, or the PR now waits for the owner's human-approved label.
```

## shop-planner

| Setting | Value |
| --- | --- |
| Schedule | On demand from the Claude app (optionally weekly, Monday 08:00) |
| Model | A stronger model than the worker (for example Opus) |
| Notifications | On: task issues created, epics drafted for approval |

Prompt:

```text
You are shop-planner for the "Mây" shop project. The repositories shop-api and shop-web are attached.

1. Run: cd shop-api && bash scripts/next-plan.sh
2. If it prints {"action":"none",...}: reply with its reason in one line and end the session now. Do nothing else.
3. Otherwise read shop-api/.claude/prompts/planner.md and follow it exactly for that JSON. Sources of truth: docs/handoff/shop-build-plan.md, docs/handoff/technical-notes.md, plans/roadmap.md and plans/epics/ in each repository, and design/ in shop-web. Do not change decisions made there.
4. Name this session "planner · <action> <epic or phase>".
5. Everything you write is in English. Never label anything approved or human-approved; the owner approves epics.
6. Finish with a summary of what you created and what the owner must review, and send a notification.
```

## What runs where

| Component | Where | Calls a model |
| --- | --- | --- |
| `scripts/next-task.sh`, `next-review.sh`, `next-plan.sh` | Start of each cloud session | No (bash + gh) |
| Implementation, review, planning | Cloud session | Yes |
| `ci`, `e2e`, `merge-gate`, `contract-publish` | GitHub Actions with `GITHUB_TOKEN` | No |
