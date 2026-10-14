# PatchPilot: Supervised Self-Healing for Apps

Build spec and Codex prompt plan for Codestorm 2026: Future Forge (Devfolio). Rename the project if you like.


## 1. How to use this document

- Create an empty GitHub repo and open it in Codex (or Antigravity).
- Paste Section 2 (Master Prompt) first. It gives the agent the full context.
- Then paste the milestone prompts in Section 11 one at a time. Test and commit after each one.
- Keep this document in the repo as SPEC.md so the agent can re-read it.

## 2. Master Prompt (paste this first)


```text
You are a senior full-stack engineer. Build "PatchPilot", a SUPERVISED SELF-HEALING system, for the hackathon Codestorm 2026: Future Forge.

What it does: a sample web app crashes. PatchPilot captures the stack trace and logs, reproduces the bug by writing a failing test, asks an LLM to propose a minimal patch, runs the full test suite in an isolated sandbox, scores the risk of the patch, and then opens a DRAFT pull request with the diff, test results and a plain-language explanation. A human approves or rejects it in a dashboard. If the agent cannot reproduce the bug, the tests fail, or the change touches a sensitive area (auth, payments, secrets), PatchPilot DECLINES and writes an honest report instead of claiming success.

Non-negotiable principles:
1. Supervised, never autonomous: never push to main or deploy. Only draft PRs (or a local branch + patch file when no GitHub token exists).
2. Evidence before claims: never mark an incident "fixed" unless a reproducing test failed before the patch and the full suite passed after it, inside the sandbox.
3. Refuse when unsure: implement the refusal rules in SPEC.md section 7.
4. Demo reliability: support LIVE mode (real LLM calls) and REPLAY mode (cached, deterministic responses) selectable from the UI.
5. One language only for the target app: Python.

Stack: Java + Spring Boot (PatchPilot backend), Python 3.11 + Flask (sample app "ShopLite"), SQLite, Docker for the sandbox (fallback to a temp-dir subprocess if Docker is unavailable), React + Vite + TypeScript + Tailwind + Framer Motion for the dashboard, Server-Sent Events for live updates. LLM access goes through a provider-agnostic interface configured by environment variables, never hard-coded keys.

UI requirement: a dark-themed, interactive, polished dashboard (details in SPEC.md section 8).

Work in small milestones. After each one: run tests, update the README, and summarize what changed. Do not move to the next milestone until the current one works. Ask me before making any decision that changes this spec.
```


## 3. Problem and pitch

Problem. Developers lose hours to the loop of noticing a bug, reading the stack trace, reproducing it, writing a fix and checking that nothing else broke. Students, startups, NGOs and small businesses often have no on-call engineer at all.

Solution. PatchPilot automates that loop and keeps a human in control. It prepares a tested, explained, risk-scored fix for a developer to review.

Tagline. "Your app breaks. PatchPilot shows its work."

What makes it different from existing auto-fix tools. The focus is trust:

- Every fix ships with evidence (failing test before, passing suite after).
- Every patch gets a risk score.
- The agent says "I won't fix this, and here is why" when it should.

## 4. Hackathon fit

Primary track to select on Devfolio: AI or Productivity and Automation. Mention the others in the description.


## 5. Architecture


```text
 ShopLite (Flask, crashes)
 |  error hook POSTs stack trace + logs + request context
 v
 PatchPilot API (Spring Boot, Java) --SSE--> Dashboard (React, dark UI)
 |
 +--> Pipeline orchestrator (state machine)
  1 Capture   -> store incident, redact secrets
  2 Triage    -> classify, locate suspect files
  3 Reproduce -> LLM writes failing test, run in sandbox, confirm it fails
  4 Patch     -> LLM proposes minimal unified diff
  5 Verify    -> apply diff in sandbox, run full suite
  6 Score     -> risk score + refusal rules
  7 Deliver   -> draft PR (GitHub API) or local branch + .patch file
  8 Review    -> human Approve / Reject / Request changes
```

Incident states: captured, triaging, reproducing, patching, verifying, scoring, ready_for_review, approved, rejected, declined, failed.


## 6. Repository structure


```text
patchpilot/
  SPEC.md
  README.md
  docker-compose.yml
  .env.example
  backend/
    pom.xml
    src/
      main/
        java/com/patchpilot/
          PatchPilotApplication.java  # Spring Boot entry point
          controller/IncidentController.java  # REST routes, SSE
          model/  # Incident, PipelineStep and Artifact entities
          pipeline/  # orchestrator, capture, triage, reproduce, patch, verify
          pipeline/RiskScorer.java
          pipeline/DeliveryService.java
          llm/LLMProvider.java  # provider interface
          llm/LLMProviders.java  # anthropic / openai / gemini adapters
          llm/ReplayProvider.java  # cached deterministic responses
          sandbox/DockerRunner.java
          sandbox/LocalRunner.java  # fallback
          policy/PatchPolicy.java  # refusal rules
        resources/application.properties
      test/java/com/patchpilot/  # backend tests
  sample_app/            # ShopLite (Flask) + its own pytest suite
    shoplite/
    tests/
    bugs/                # seeded bug switches
  dashboard/
    src/ (pages, components, hooks, lib)
  demo/
    replay_cache/
    demo_script.md
```


## 7. Core logic


### 7.1 Sample app and seeded bugs

ShopLite is a tiny Flask shop with endpoints /users/<id>, /orders?page=, /cart/discount, /inventory/sync and /payments/charge. A bugs/ toggle lets you switch each bug on or off for the demo. It has a healthy pytest suite that passes when no bug is active.

The demo shows one success, one refusal and one honest failure.


### 7.2 Error capture

ShopLite installs an error hook that sends: exception type, message, stack trace, request method/path/params, the last 50 log lines, timestamp and app version. Redact tokens, passwords and emails before storing.


### 7.3 Reproduce-first rule

The agent must write a test that fails for the same reason as the incident before it may propose a patch. If the test passes or fails for a different reason, retry up to 2 times, then decline.


### 7.4 Patch rules

- Minimal unified diff only, ideally under 30 changed lines and 2 files.
- No new dependencies. No edits to test files other than adding the reproducing test.
- No fixes that merely hide the error (bare except: pass, silently returning defaults without a reason). Detect these patterns and penalize or reject.

### 7.5 Risk score (0 to 100, lower is safer)

Verdicts: 0 to 30 Safe to review, 31 to 60 Review carefully, above 60 or any hard rule: Declined.


### 7.6 Refusal rules (PatchPolicy.java)

Decline and write a report when any of these hold:

- The stack trace or diff touches auth, payments, secrets, migrations or infrastructure files.
- The bug cannot be reproduced after retries.
- The full test suite fails after the patch.
- The diff exceeds the size limit.
- The root cause appears to be an external service, configuration or data issue.
Every decline produces a Decline Report: what was tried, what evidence was found, and what a human should check next.


### 7.7 Delivery

If GITHUB_TOKEN is set, create a branch patchpilot/incident-<id> and open a draft PR with the diff, reproducing test, test results, risk score and explanation. If no token exists, create a local branch and a downloadable .patch file, and show the same content in the UI.


## 8. Dashboard UI specification (dark and interactive)


### 8.1 Design system

- Theme: dark only. Background #0B0F17, surface #111827, raised surface #1A2233, border #243049.
- Text: primary #E6EAF2, muted #8B97AD.
- Accents: electric cyan #22D3EE (primary), emerald #34D399 (success), amber #FBBF24 (warning), rose #FB7185 (danger), violet #A78BFA (agent activity).
- Type: Inter for UI, JetBrains Mono for code, logs and stack traces.
- Style: rounded-xl cards, subtle 1px borders, soft glows on active elements, glassy top bar. Respect prefers-reduced-motion.
- Fully responsive down to tablet width, with accessible contrast and visible focus rings.

### 8.2 Pages and components

A. Overview page

- Stat cards with animated counters: incidents today, auto-prepared fixes, declined, average time to fix prepared.
- Live incident feed (SSE) with status chips and filter chips (All, Needs review, Fixed, Declined, Failed).
- Small sparkline of incidents over time and a "trust meter" showing the share of fixes that passed all checks.
- Search box and a command palette on Ctrl/Cmd+K.
B. Incident detail page (the hero screen)

- Animated pipeline stepper with the 8 stages. The active stage pulses in violet, completed stages turn green, declined or failed stages turn rose or amber.
- Live agent terminal that streams reasoning summaries and sandbox output, with copy and auto-scroll toggle.
- Tabs: Stack trace (with highlighted suspect line), Reproducing test, Patch diff (side by side or inline toggle), Test results (pass/fail per test), Explanation (plain language).
- Risk gauge: a semicircle gauge from 0 to 100 with a breakdown drawer showing each factor and its contribution.
- Evidence checklist: "Bug reproduced", "Fix applied", "Full suite passes", "No sensitive files touched", "No error swallowing". Each is a check or a cross with a tooltip.
- Action bar: Approve, Reject (with reason), Request another attempt, Download patch, Open PR. Approve needs a confirmation modal and shows a toast.
- For declined incidents, show the Decline Report prominently with a clear "Why PatchPilot refused" card.
C. Demo Control panel (slide-over drawer)

- Buttons to trigger each seeded bug in ShopLite ("Break the app: Pagination", "Break the app: Payments", and so on).
- Mode switch: LIVE or REPLAY. Speed slider for replay.
- A mini ShopLite preview showing the app's health: green when healthy, red when broken, green again after the fix is approved and applied.
- "Reset demo" button.
D. Settings page

- LLM provider and model, sandbox mode (Docker or local), GitHub repo and token status, risk thresholds, and the sensitive-path list.

### 8.3 Interaction details

- Smooth page and stage transitions with Framer Motion, toast notifications, skeleton loaders, empty states with illustrations.
- Keyboard shortcuts: J/K to move through incidents, A approve, R reject, ? for a shortcut help dialog.
- Diff viewer with line hover, collapsible unchanged regions and copy per hunk.
- Everything updates live without page refresh.

## 9. Backend API

Environment variables (.env.example): LLM_PROVIDER, LLM_API_KEY, LLM_MODEL, GITHUB_TOKEN, GITHUB_REPO, SANDBOX_MODE, DEMO_MODE (live or replay). Never commit real keys.


## 10. Safety and quality guardrails for the agent

- No real secrets in the repo. Provide .env.example only.
- The sandbox runs with no network access, a timeout, and CPU and memory limits.
- The agent patches only a copy of ShopLite inside the sandbox, never the original directly.
- All LLM output is validated (diff must apply cleanly, only allowed files) before use.
- Backend tests for PatchPolicy.java, RiskScorer.java and diff validation are mandatory.
- Every claim in the UI comes from a recorded pipeline artifact, never from LLM text alone.

## 11. Milestone prompts (paste one at a time)


### Milestone 1: Skeleton and sample app


```text
Create the monorepo structure from SPEC.md section 6. Build the ShopLite Flask app with the endpoints in section 7.1, a healthy pytest suite that passes, and a bug-toggle system for the 5 seeded bugs. Add an error hook that POSTs a redacted incident payload (stack trace, request context, last 50 log lines) to http://localhost:8000/api/incidents. Add a README with setup steps. Confirm every seeded bug triggers the right exception and that the healthy suite still passes with bugs off.
```


### Milestone 2: Backend core


```text
Build the Java Spring Boot backend: SQLite persistence for incidents, pipeline steps and artifacts; the incident state machine; the routes in section 9; and a Server-Sent Events stream that emits an event on every state change. Configure the server to use port 8000 so the ShopLite error hook can post to the existing URL. Use a stub orchestrator that just advances states with delays so the frontend can be built against it. Add backend tests.
```


### Milestone 3: Sandbox runner


```text
Implement the sandbox: copy the sample app into a temp directory, run its pytest suite in a Docker container with no network, a timeout and memory limits, and return structured results (per-test pass/fail, stdout, duration). Add a local-subprocess fallback when Docker is unavailable. Add tests proving that a seeded bug causes a failure in the sandbox and does not modify the original files.
```


### Milestone 4: LLM layer and reproduce step


```text
Create the provider-agnostic LLM interface in Java with at least two adapters and a REPLAY provider that serves cached JSON responses from demo/replay_cache keyed by a hash of the prompt. Implement triage and the reproduce step: the model writes a failing pytest test for the incident; run it in the sandbox; accept it only if it fails with the same exception type. Allow 2 retries, then mark the incident declined with a reason. Use structured JSON output and validate it.
```


### Milestone 5: Patch, verify and score


```text
Implement the patch step (minimal unified diff, validated to apply cleanly and to touch only allowed files), the verify step (apply in the sandbox, run the full suite plus the reproducing test), PatchPolicy.java with the refusal rules from section 7.6, and RiskScorer.java with the risk model from section 7.5, including error-swallowing detection. Add thorough unit tests for policy, scoring and diff validation. Never mark an incident fixed unless the evidence rules in section 7.3 and 7.5 are met.
```


### Milestone 6: Delivery


```text
Implement DeliveryService.java: with a GitHub token, create a branch and a DRAFT pull request containing the diff, reproducing test, test results, risk score and plain-language explanation; without a token, create a local git branch and a downloadable .patch file. Generate a Decline Report for declined and failed incidents. Wire approve/reject/retry endpoints.
```


### Milestone 7: Dashboard


```text
Build the React + Vite + TypeScript + Tailwind dashboard exactly as described in SPEC.md section 8: dark design system, Overview page with live feed and animated counters, the Incident detail hero screen (animated stepper, live agent terminal, tabs, diff viewer, risk gauge with breakdown, evidence checklist, action bar), the Demo Control drawer, Settings page, command palette, and keyboard shortcuts. Connect everything to the backend REST and SSE endpoints. Use Framer Motion for transitions and respect reduced-motion preferences.
```


### Milestone 8: Demo hardening and polish


```text
Record REPLAY cache entries for all 5 seeded bugs by running them in LIVE mode, then verify REPLAY mode reproduces the full flow deterministically and quickly. Add the demo_script.md (90-second walkthrough), a health indicator for ShopLite that turns green after a fix is approved, a one-command start (docker-compose up), a polished README with an architecture diagram and screenshots, an MIT license, and CI that runs all tests. Fix any bugs you find and list remaining known issues honestly.
```


## 12. Demo script (about 90 seconds)

- 0:00 Show the healthy ShopLite and the dashboard. State the problem in one sentence.
- 0:10 Click Break the app: Pagination. ShopLite goes red and an incident appears instantly.
- 0:20 Open the incident. Watch the stepper: reproduce (test fails), patch, verify (suite passes).
- 0:45 Show the diff, the risk gauge (Safe to review) and the evidence checklist. Click Approve. ShopLite turns green.
- 1:05 Trigger the Payments bug. PatchPilot declines and shows the Decline Report.
- 1:20 Close with the line: "PatchPilot doesn't just fix bugs. It proves the fix and knows when to stop."
Keep a pre-recorded fallback video in case the live demo misbehaves.


## 13. Devfolio submission checklist

- ☐ Project name, tagline and a clear description (problem, solution, how it works, challenges, what's next)
- ☐ Public GitHub repo with regular commits, a strong README, architecture diagram and screenshots
- ☐ Demo video of 2 to 3 minutes (YouTube or Loom), following the script above
- ☐ Live deployed dashboard link, or REPLAY mode online if the sandbox cannot be hosted
- ☐ Tech stack listed, including the AI coding tools you used (disclose Codex or Antigravity if the rules ask for it)
- ☐ Correct track or category selected
- ☐ Teammates added and invites accepted
- ☐ Submit clicked before the deadline (a saved draft is not a submission)
- ☐ Re-check the event page for rules on AI tool usage, originality and required items

## 14. Honest limitations (put these in your README and pitch)

- Works best on small, reproducible, logic-level bugs. It does not claim to fix concurrency, infrastructure or data issues.
- A passing test suite does not guarantee a correct fix, which is why a human reviews every patch.
- Current scope is Python projects with a pytest suite. Support for more languages is future work.
Roadmap: support for JavaScript and Java, a GitHub App for one-click install, Slack and email alerts, a learning loop from approved and rejected fixes, and a security mode that scans for vulnerable patterns.


Hackathon category | How PatchPilot fits
Artificial Intelligence solutions | LLM agent for diagnosis, test writing and patching
Productivity and automation software | Automates triage, reproduction, fixing and PR creation
Web applications and platforms | React dashboard plus Spring Boot backend
Open-source projects | Released as a pluggable tool with an MIT license
Real-world problem | Downtime and slow bug fixes, especially for small teams
Cybersecurity (secondary) | Flags crashes caused by unsafe input and refuses to touch sensitive code

# | Bug | Expected PatchPilot outcome
1 | None access when a user has no profile field | Fixed, low risk
2 | Off-by-one in pagination (last page empty or duplicated) | Fixed, low risk
3 | Division by zero in the discount calculation (empty cart) | Fixed, low-medium risk
4 | Crash in /payments/charge | Declined (sensitive area)
5 | /inventory/sync fails because an external service times out | Failed honestly (cannot reproduce without the external service)

Factor | Effect
Lines changed | More lines, higher risk
Files touched | More files, higher risk
Sensitive path (auth, payments, secrets, migrations) | Forces a decline
Reproducing test added and confirmed failing before the patch | Lowers risk
Full suite passes after the patch | Required, otherwise failed
Error-swallowing pattern detected | Raises risk sharply
LLM self-reported confidence (low weight) | Minor adjustment

Method | Path | Purpose
POST | /api/incidents | Ingest an error from the sample app
GET | /api/incidents | List with filters
GET | /api/incidents/{id} | Full detail, steps, artifacts
GET | /api/stream | SSE of pipeline events
POST | /api/incidents/{id}/approve | Human approval
POST | /api/incidents/{id}/reject | Rejection with reason
POST | /api/incidents/{id}/retry | Another attempt
POST | /api/demo/trigger/{bug_id} | Switch a seeded bug on
POST | /api/demo/reset | Reset sample app and data
GET/PUT | /api/settings | Read and update settings
