# Scheduled tasks

The project is built by Claude Code cloud sessions that three scheduled tasks start. The owner
creates them in the Claude app; this file holds their exact prompts and settings. GitHub Actions
never calls a model: it runs CI, `pr-policy` and `contract-publish` only.

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
| Repository hooks | `.claude/settings.json` hooks are not loaded in multi-repository sessions | No guard hook; the gate is CI (`protected-paths`) |

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

There is no approval gate: PRs auto-merge once the required checks are green. The `agent-approved`
label only records a review.

## GitHub event triggers (faster turnaround)

The hourly schedules stay as a fallback (resume after an interrupted run, missed events). GitHub
event triggers start runs as soon as there is work. They are added in the web UI on each task's
page (**Add trigger → GitHub event**); the Claude GitHub App must be installed on both
repositories. A task's GitHub triggers can watch only one repository, so each role has a second
task for `shop-web`:

| Task | Repositories (first one is watched) | Schedule | GitHub triggers |
| --- | --- | --- | --- |
| `shop-worker` | `shop-api`, `shop-web` | `5 * * * *` | worker trigger below, on `shop-api` |
| `shop-worker-web` | `shop-web`, `shop-api` | none | worker trigger below, on `shop-web` |
| `shop-reviewer` | `shop-api`, `shop-web` | `35 * * * *` | reviewer trigger below, on `shop-api` |
| `shop-reviewer-web` | `shop-web`, `shop-api` | none | reviewer trigger below, on `shop-web` |

The `-web` tasks use the same prompt, model and environment as their counterpart.

| Role | Event | Filters |
| --- | --- | --- |
| reviewer | Pull request → labeled | Labels is one of `review`; Is draft equals `false` |
| worker | Pull request → closed | Is merged equals `true` |

Each task has exactly one GitHub trigger. There is no trigger on `changes-requested`: the picker
already sends the worker to PRs with requested changes before new work, so the next worker run
(the next merged PR or the hourly schedule) picks them up.

Several worker runs can start together (an event and the hourly run, or events in both
repositories). Every worker run calls `scripts/claim.sh` right after the picker: it posts its
heartbeat, waits 15 seconds, and continues only if no older fresh worker heartbeat exists; the
others end at once.

Why these are safe to fire often: the pickers decide what to do, so an extra run ends at once with
`{"action":"none"}`; heartbeats and `claim.sh` keep a single worker active; the reviewer skips a PR another
reviewer is already on. Workers add `review` only after CI is green, so a reviewer started by the
label finds green checks. Each run costs some quota even when it ends at once, and GitHub events
above the per-task hourly cap are dropped (the hourly schedule then catches up).

## shop-worker

| Setting | Value |
| --- | --- |
| Schedule | Hourly; then edit to Custom `5 * * * *` (minute 5, UTC) |
| Model | Sonnet (default) |
| Repositories / environment | Both repositories / the environment above |

Prompt:

```text
You are shop-worker for the "Mây" shop project. The repositories are at /home/user/shop-api and /home/user/shop-web.

0. First, start both checkouts from main (event-triggered runs start from the PR head commit): for r in shop-api shop-web; do git -C /home/user/$r fetch -q origin main && git -C /home/user/$r checkout -q --detach origin/main; done
1. Run: cd /home/user/shop-api && bash scripts/next-task.sh
2. If it prints {"action":"none",...} or {"action":"error",...}: reply with its reason in one line and end the session now. Do nothing else.
3. Otherwise read /home/user/shop-api/.claude/prompts/worker.md (and triage.md for "triage") and follow it exactly for that JSON. Read the CLAUDE.md of the repository you work in before changing anything.
4. GitHub access is REST only: use /home/user/shop-api/scripts/gh-task.sh, never gh pr or gh issue. Branches must start with claude/.
5. Everything you write to GitHub and the repositories is in English. Never force push, push to main, delete branches, change repository settings, or add approved or infra-change. When the issue is unclear, stop and comment instead of guessing.
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

0. First, start both checkouts from main (event-triggered runs start from the PR head commit): for r in shop-api shop-web; do git -C /home/user/$r fetch -q origin main && git -C /home/user/$r checkout -q --detach origin/main; done
1. Run: cd /home/user/shop-api && bash scripts/next-review.sh
2. If it prints {"action":"none",...} or {"action":"error",...}: reply with its reason in one line and end the session now. Do nothing else.
3. Otherwise read /home/user/shop-api/.claude/prompts/reviewer.md and follow it exactly for that PR, including every checklist item.
4. GitHub access is REST only: use /home/user/shop-api/scripts/gh-task.sh, never gh pr or gh issue.
5. Everything you write is in English. Never push commits to the PR branch.
6. Finish with a one-line verdict and what the owner must do, if anything.
```

## shop-worker-web and shop-reviewer-web

GitHub event triggers can watch only the task's first repository, so each role has a twin whose
first repository is `shop-web`. The twins exist only to react to `shop-web` events.

| Setting | shop-worker-web | shop-reviewer-web |
| --- | --- | --- |
| Repositories (order matters) | `shop-web`, then `shop-api` | `shop-web`, then `shop-api` |
| Schedule | None (GitHub triggers only) | None (GitHub triggers only) |
| Model / environment | Same as `shop-worker` | Same as `shop-reviewer` |
| Prompt | Exactly the `shop-worker` prompt | Exactly the `shop-reviewer` prompt |

The prompts are deliberately shared: the pickers look at both repositories and pick the oldest or
most urgent work wherever it is, and `claim.sh` keeps a single worker active. A web-only prompt
would let a second worker run in parallel.

### Why every prompt starts from main

When the PR that fired a trigger belongs to the task's first repository, the run starts from the
PR's head commit. Without step 0, a run started by a PR would read the role prompt and run the
picker and `gh-task.sh` from that PR's own code, so a PR could change how it is reviewed. Step 0
puts both checkouts on `main`; the reviewer then reads the PR's code in a separate worktree
(`/tmp/pr-<n>`), never by switching the checkout that holds the scripts.

## shop-planner

| Setting | Value |
| --- | --- |
| Schedule | Run on demand from the task page (optionally weekly, Monday 08:00) |
| Model | A stronger model than the worker (for example Opus) |
| Repositories / environment | Both repositories / the environment above |

Prompt:

```text
You are shop-planner for the "Mây" shop project. The repositories are at /home/user/shop-api and /home/user/shop-web.

0. First, start both checkouts from main (event-triggered runs start from the PR head commit): for r in shop-api shop-web; do git -C /home/user/$r fetch -q origin main && git -C /home/user/$r checkout -q --detach origin/main; done
1. Run: cd /home/user/shop-api && bash scripts/next-plan.sh
2. If it prints {"action":"none",...} or {"action":"error",...}: reply with its reason in one line and end the session now. Do nothing else.
3. Otherwise read /home/user/shop-api/.claude/prompts/planner.md and follow it exactly for that JSON. Sources of truth: docs/handoff/shop-build-plan.md, docs/handoff/technical-notes.md, plans/roadmap.md and plans/epics/ in each repository, and design/ in shop-web. Do not change decisions made there.
4. GitHub access is REST only: use /home/user/shop-api/scripts/gh-task.sh, never gh pr or gh issue. Branches must start with claude/.
5. Everything you write is in English. Never label anything approved; the owner approves epics.
6. Finish with a summary of what you created and what the owner must review.
```

## What runs where

| Component | Where | Calls a model |
| --- | --- | --- |
| `scripts/next-task.sh`, `next-review.sh`, `next-plan.sh`, `gh-task.sh` | Cloud session | No (bash + gh REST) |
| Implementation, review, planning | Cloud session | Yes |
| `ci`, `e2e`, `pr-policy`, `contract-publish` | GitHub Actions with `GITHUB_TOKEN` | No |
