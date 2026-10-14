# Devfolio submission draft

**Project:** PatchPilot  
**Tagline:** Your app breaks. PatchPilot shows its work—and knows when to stop.  
**Suggested category:** Productivity and Automation  
**Tech stack:** Java 17, Spring Boot, SQLite, Python 3.11, Flask, pytest, Docker sandbox runner, React, TypeScript, Vite, Server-Sent Events  
**AI tools:** Codex was used during development; disclose it in the field required by the event rules.

## Description

Small teams can spend hours finding, reproducing, and triaging application bugs. PatchPilot is a supervised repair prototype that captures redacted error reports from a demo shop app, stores them in a Java backend, and streams their status to a dashboard. For four known seeded bugs, a prompt-hash replay fixture supplies a small patch. PatchPilot first confirms a failing regression case, applies the patch only to a temporary copy, and runs the full pytest suite plus the regression check before making the diff available for review. The sandbox has a network-disabled Docker option.

Safety is a core design goal: payment incidents are declined, and no patch is marked ready without reproduction and verification evidence. The replay fixtures are deterministic; the current build does not call a live LLM or support arbitrary projects. Risk scoring is basic, and local Git branch and pull-request delivery remain future work. The project is a working prototype, not a finished autonomous repair agent.

## Challenge and what is next

The main challenge is preserving trust when a tool handles production-like errors. PatchPilot redacts common email and secret patterns, isolates sandbox execution, and makes a human review part of the workflow. Next, we plan to add live provider adapters, robust patch validation and risk policies, local branch creation, and GitHub draft PR delivery.

## Links to add before submitting

- Public source repository: **create and paste URL**
- Demo video (2–3 minutes): **record and paste URL**
- Live demo: **not deployed; run locally using README**
- Teammates: **confirm team setup in Devfolio**
