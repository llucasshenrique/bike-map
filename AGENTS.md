# Working on this repo

This project is developed through **Orca-managed worktrees**, with multiple coding agents
(`agy` / Antigravity CLI, `claude`, ...) working in parallel worktrees, coordinated by a
controlling agent (you) via the `orca` CLI. Worktree locations are machine/Orca-config
dependent — never hardcode a path to them; resolve with `orca worktree list --repo <selector>
--json` or `orca worktree show --worktree <selector>` instead. Load the `orca-cli` and
`orchestration` skills at the start of any session that involves spawning, coordinating, or
checking in on other agents in this repo — do not wait to be reminded.

## Resolving the CLI

- Outside an Orca-managed terminal on Linux: use `orca-ide` (never bare `orca`, which resolves
  to the GNOME screen reader). Inside Orca-managed terminals or other environments, follow the
  `orca-cli` skill's resolution rules.

## Coordinating other agents: use orchestration, not manual polling

When dispatching work to another agent (a separate worktree/terminal), prefer the `orca
orchestration` layer (`run-create`, `task-create`, `worker-start`, `check --wait`,
`worker-release`) over manually looping `terminal read`/`sleep`. This gives durable Run/Task/
Dispatch tracking and lets the worker report back via `worker_done` instead of you polling raw
terminal output.

**`agy` (Antigravity CLI) caveats** — `worker-start --agent agy` is rejected (not a recognized
built-in agent id). Instead:
1. `terminal create --worktree <selector> --command "agy --dangerously-skip-permissions"`
2. New worktrees are auto-trusted by this repo's `orca.yaml` setup hook, and Orca's launch
   script now auto-authorizes `agy` in new worktrees — you do **not** need to wait for a trust
   prompt before sending the task prompt; send it right after creating the terminal/worktree.
3. `agy`'s provider is reported `unsupported` for structured turn-observation, so
   `worker-start --terminal <handle>` may not cleanly confirm readiness/settlement. Work around
   this by explicitly telling the agent, in its task prompt, to run
   `orca orchestration worker_done --task <id> --dispatch <id> --run <id> --outcome
   succeeded|failed --summary "..."` itself once done, and verify completion independently via
   `git log`/`git status` in its worktree if the `worker_done` message is rejected or never
   arrives (stale dispatch handle after a manual re-attach is a known failure mode — the fix is
   to verify via git directly and `task-update --status completed` manually).
4. `agy` sign-in occasionally hangs indefinitely — usually an account-level concurrency limit
   when multiple `agy` sessions are open (including ones in the user's *other*, unrelated repos
   -- check `ps aux | grep agy` and `readlink /proc/<pid>/cwd` before assuming a hang is yours
   to fix, and never kill a process that isn't in one of this repo's worktrees). If a launch
   hangs >30s, close it and retry once; if it keeps hanging, fall back to `claude` for that slot
   rather than blocking on it, unless the independent-test-authorship rule (below) requires a
   specific agent type.

## Concurrency limits

The user controls how many agents may run at once and which tools they're split across, and
changes this over time — **never assume a number, recall or ask for the current limit before
dispatching**. Once you have it: respect it strictly (it does not include you as coordinator
unless told otherwise), queue additional work rather than exceeding it, and re-derive the limit
each session rather than trusting a number from an earlier conversation.

## When you are the one being orchestrated

If your session carries an injected preamble with a Task/Dispatch ID (i.e. you were started by
`worker-start`/`dispatch`), that preamble is authoritative:
- Do only the current Task; use its `ask` command for a blocking question to the coordinator
  instead of opening a local prompt it can't see.
- Send heartbeats only at the cadence it specifies — a heartbeat proves liveness, not progress.
- Check for coordinator follow-ups at natural checkpoints and once more right before finishing.
- Report completion exactly once, from your own terminal, with `worker_done` (or the manual
  fallback in the `agy` notes above if your tooling can't reach that command directly): a short
  summary, both lifecycle IDs, and an explicit succeeded/failed outcome — never leave the
  outcome implicit in prose. Then stop; don't keep polling or start new work uninstructed.

## Independent test authorship (hard rule)

The agent that writes tests for a feature must be a **different agent/session** than the one
that wrote the feature code — never let the same agent grade its own work. When QA/test-writing
capacity is constrained (e.g. `agy` unavailable), the coordinator itself counts as an
independent reviewer and may write the tests directly rather than reusing the same agent that
wrote the feature.

## Android build environment

- SDK: managed via `mise.toml` (`android-cli`/`android-sdk` tools) for reproducible setup. If
  `./gradlew` can't find the SDK outside Orca's own tooling, check `local.properties`/
  `$ANDROID_HOME`/`$ANDROID_SDK_ROOT` for the actual local install path rather than assuming
  one — it varies by machine.
- Default to **Paparazzi** (`app.cash.paparazzi` plugin, already applied to `:app`) for any
  UI-visual change: `./gradlew :app:recordPaparazziDebug --tests "*YourTest*"` renders Compose
  composables to PNG via a JVM-only layoutlib, no device needed, and works reliably regardless
  of host. The user explicitly wants screenshots (or short recordings, where feasible) of UI
  changes so they can review without building/launching the app themselves — treat this as a
  standing requirement for UI PRs, not a one-off ask.
- If a task genuinely needs a real device/emulator (e.g. instrumented tests, manual QA that
  Paparazzi can't cover), do a quick timed check that an AVD actually boots on the current host
  before committing to that path, and fall back to Paparazzi/unit-level coverage if it doesn't
  boot within a couple of minutes rather than debugging emulator infrastructure at length.

## CI

Every branch should carry `.github/workflows/android-ci.yml` (JDK 17, Gradle, `./gradlew
testDebugUnitTest`, `./gradlew lintDebug`, `./gradlew assembleDebug`, triggered on
`pull_request`/`push` to `main`). `app/lint-baseline.xml` on `main` captures pre-existing lint
findings so CI only gates *new* issues — regenerate it with `./gradlew updateLintBaseline` if a
change legitimately needs to add to the baseline, don't just delete/ignore failures.

## Branch naming

Conventional `type/description` (`feat/...`, `fix/...`, `chore/...`, `test/...`), no
user-prefixed names. **Never rename a branch with an open PR via the GitHub REST/`gh api`
branch-rename endpoint** — it closes the PR instead of retargeting it (confirmed by direct
experience: 11 PRs were closed this way and had to be reopened fresh against the renamed
branches, reusing the original title/body). If a branch genuinely needs renaming after a PR is
open, open a fresh PR against the new name and close the old one, or just push new commits to
the existing (correctly-named) branch instead of renaming.

## Merging images into PR bodies

`gh pr create --body` with a relative repo path for an image (e.g.
`![x](app/src/test/snapshots/images/foo.png)`) will **not** render on GitHub — use a
`raw.githubusercontent.com/<owner>/<repo>/<branch>/<path>` URL to a file already committed on
that PR's branch instead.
