# PatchPilot demo walkthrough (about 90 seconds)

## Before recording

Start the backend, ShopLite, and dashboard using the README. Open the dashboard and confirm the API indicator is online. Do not present a local simulation as an AI-generated patch: this prototype does not generate patches yet.

## Walkthrough

- **0:00 — The problem:** “A small team can lose time just reproducing a crash and understanding what happened. PatchPilot captures the evidence and keeps a human in the loop.”
- **0:10 — The system:** Show the dashboard and explain that ShopLite sends a redacted report to a Java API, which persists it and streams status to this UI.
- **0:22 — Trigger pagination:** Open Demo controls, choose **Pagination**, and trigger it. Show the 500 in ShopLite and the captured incident in the feed.
- **0:38 — Inspect evidence:** Open the incident and point out the route, exception, timestamp, and pipeline status. Explain honestly: “This build records and triages incidents, but patch generation and verification are not implemented. It marks this incident failed rather than pretending it fixed the bug.”
- **0:58 — Show refusal:** Trigger **Payments (refusal demo)**. Open its incident and show the refusal report. Explain that payment paths are treated as sensitive.
- **1:15 — Close:** “The working foundation is incident capture, persistence, a sandbox runner, and a live dashboard. The next milestone is a replayable reproduce-and-verify loop that records test evidence before it can propose a patch.”

## Recording notes

Keep the API health page and terminal windows out of the recording unless they help explain the demo. Capture the actual running dashboard; do not add mock fix results or screenshots that imply unimplemented features.
