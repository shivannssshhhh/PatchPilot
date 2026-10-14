# PatchPilot

**Your app breaks. PatchPilot shows its work—and knows when to stop.**

PatchPilot is a supervised bug-repair prototype. ShopLite sends a redacted incident to a Java API, which stores it and streams status changes to a React dashboard. The dashboard lets a reviewer inspect the incident and retry or reject it. Sensitive payment incidents are declined.

> **Prototype status:** deterministic replay fixtures support four known ShopLite bugs. For those cases, the backend checks the reported exception, confirms a failing reproduction in a temporary copy, applies a cached minimal edit there, runs the suite and regression check, calculates a basic risk score, and offers the verified diff for human review. Payment incidents are declined. There is no live LLM adapter, arbitrary-project support, local Git branch creation, or GitHub PR delivery. This is a hackathon demo, not a production repair service.

## Architecture

```text
ShopLite (Flask, :5000) ── redacted incident ──▶ PatchPilot API (Spring Boot, :8000)
                                                       │ SQLite + SSE
                                                       ▼
                                              Dashboard (React, :5173)
                                                       │
                                   Local/Docker sandbox runner (prototype)
```

## Run locally on Windows

Use three PowerShell windows from the project root.

### 1. Start the backend

```powershell
cd backend
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd spring-boot:run
```

Wait for Spring Boot to start. Verify `http://localhost:8000/api/health` returns `status: healthy`.

### 2. Start ShopLite

```powershell
cd sample_app
& "$env:LOCALAPPDATA\Programs\Python\Python311\python.exe" -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m shoplite
```

ShopLite listens on `http://localhost:5000`. All seeded bugs start disabled. The demo drawer can turn a bug on and trigger it; or use:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:5000/__demo/bugs `
  -ContentType 'application/json' -Body '{"active":["pagination"]}'
Invoke-WebRequest 'http://localhost:5000/orders?page=2'
```

The expected 500 produces a redacted incident. Check it at `http://localhost:8000/api/incidents`.

### 3. Start the dashboard

```powershell
cd dashboard
npm ci
npm run dev
```

Open `http://localhost:5173`. The dashboard calls the API on port 8000 and ShopLite on port 5000. Keep all three processes running. The demo controls are local-only and intended for localhost.

## API

- `POST /api/incidents` captures a redacted incident; `GET /api/incidents` lists incidents.
- `GET /api/incidents/{id}` returns incident details, pipeline steps, and evidence artifacts.
- `GET /api/incidents/events` and `/api/incidents/{id}/events` stream status changes over SSE.
- `GET /api/incidents/{id}/patch` downloads a patch only after replay, reproduction, and full-suite verification succeed.
- `POST /api/incidents/{id}/approve`, `/reject`, and `/retry` record review actions.

## Tests and build

```powershell
# ShopLite
cd sample_app
python -m pytest

# Backend (from backend/; set JAVA_HOME as above)
.\mvnw.cmd test

# Dashboard
cd ..\dashboard
npm ci
npm run build
```

## Sandbox

Sandbox mode defaults to `auto`: Docker is used if its daemon is available; otherwise pytest runs in a time-limited temporary copy. Docker test containers disable networking and set CPU, memory, process, and filesystem limits. The local subprocess fallback does not provide network isolation and is intended only for the trusted ShopLite demo. Docker execution has not been validated on every host.

## Safety and scope

- ShopLite and the backend redact common email and secret patterns before storing incident payloads.
- PatchPilot does not push to a default branch, open a pull request, deploy, or apply a patch to the original sample app.
- No API keys are required by this prototype. Never commit real credentials.
- A passing test suite is not proof that a change is correct; human review remains required.
- Current tested target is the bundled Python/pytest sample app.

## Milestone status

| Milestone | Status |
| --- | --- |
| 1. ShopLite and incident capture | Implemented; pytest was passing in local checks |
| 2. Java API, persistence, and SSE | Implemented; API integration checks were passing in local checks |
| 3. Local/Docker sandbox runner | Implemented; local runner checked; Docker daemon path still needs a live run |
| 4. Provider-agnostic LLM and reproduce step | Partial: prompt-hash replay provider and reproduce-first check for four fixtures; no live model adapters or retries |
| 5. Patch, verify, risk score, and refusal policy | Partial: allowlisted replay patch, sandbox suite/regression verification, basic risk score, and payment refusal; no general diff validator or comprehensive policy model |
| 6. Local patch or draft PR delivery | Partial: verified patch downloads from the API; no local Git branch or draft PR creation |
| 7. React incident dashboard | Partial: live feed, detail/evidence, demo controls, status actions, basic risk gauge, and patch download; no settings, terminal, diff viewer, or command palette |
| 8. Replay demo, CI, compose, and polish | Partial: deterministic fixtures, walkthrough, CI, MIT license, and setup docs; no compose startup or recorded screenshots/video |
