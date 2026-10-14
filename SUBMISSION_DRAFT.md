# Devfolio submission draft

**Project:** PatchPilot  
**Tagline:** Your app breaks. PatchPilot shows its work—and knows when to stop.  
**Suggested category:** Productivity and Automation  
**Tech stack:** Java 17, Spring Boot, SQLite, Python 3.11, Flask, pytest, Docker sandbox runner, React, TypeScript, Vite, Server-Sent Events  
**AI tools:** Codex was used during development; disclose it in the field required by the event rules.

## Description

Small teams can spend hours finding, reproducing, and triaging application bugs. PatchPilot is a supervised self-healing prototype that captures redacted error reports from a demo shop app, stores them in a Java backend, and streams their status to a dashboard. A local sandbox runner can execute the sample app's pytest suite in a temporary copy, with a network-disabled Docker option. The dashboard includes a live incident feed, evidence details, status updates, and controls for demonstrating seeded failures.

Safety is a core design goal: payment incidents are declined, and the current build never claims an incident is fixed. Automated LLM triage, test generation, patch verification, risk scoring, and pull-request delivery remain future work. The project demonstrates the trustworthy incident-capture and review foundation, not a finished autonomous repair agent.

## Challenge and what is next

The main challenge is preserving trust when a tool handles production-like errors. PatchPilot redacts common email and secret patterns, isolates sandbox execution, and makes a human review part of the workflow. Next, we plan to add deterministic replay fixtures, a provider interface, reproduce-first tests, verified minimal patches, risk scoring, and local patch delivery before connecting any live LLM or GitHub integration.

## Links to add before submitting

- Public source repository: **create and paste URL**
- Demo video (2–3 minutes): **record and paste URL**
- Live demo: **not deployed; run locally using README**
- Teammates: **confirm team setup in Devfolio**
