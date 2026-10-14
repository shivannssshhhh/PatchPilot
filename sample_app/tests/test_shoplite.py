from __future__ import annotations

import json
import os

import pytest

from shoplite import create_app
from shoplite.incidents import TailLogHandler, build_incident, redact


@pytest.fixture
def client():
    app = create_app({"TESTING": True, "ACTIVE_BUGS": set()})
    return app.test_client()


def test_health_and_healthy_endpoints(client):
    assert client.get("/health").json["status"] == "healthy"
    assert client.get("/users/1").json["name"] == "Asha Rao"
    assert len(client.get("/orders?page=1").json["items"]) == 2
    assert client.post("/cart/discount", json={"code": "FORGE10", "subtotal": 10}).json["total"] == 9
    assert client.post("/inventory/sync", json={"items": []}).json["count"] == 0
    assert client.post("/payments/charge", json={"amount": 5}).json["status"] == "charged"


@pytest.mark.parametrize(
    ("bug", "method", "path", "payload", "exception"),
    [
        ("users", "get", "/users/1", None, "KeyError"),
        ("pagination", "get", "/orders?page=2", None, "TypeError"),
        ("discount", "post", "/cart/discount", {"code": "FORGE10", "subtotal": 10}, "KeyError"),
        ("inventory", "post", "/inventory/sync", {"items": []}, "ZeroDivisionError"),
        ("payments", "post", "/payments/charge", {"amount": 5}, "RuntimeError"),
    ],
)
def test_each_seeded_bug_returns_an_incident(
    client, monkeypatch, bug, method, path, payload, exception
):
    reports = []
    monkeypatch.setattr("shoplite.incidents._send_incident", lambda report, _app: reports.append(report))
    client.post("/__demo/bugs", json={"active": [bug]})
    response = getattr(client, method)(path, json=payload) if payload else getattr(client, method)(path)
    if os.environ.get("PATCHPILOT_FIXED_BUG") == bug:
        assert response.status_code == 200
        assert reports == []
        return
    assert response.status_code == 500
    assert response.json == {"error": "internal server error", "incident": "captured"}
    assert reports[0]["exceptionType"] == exception


def test_demo_control_rejects_unknown_bug(client):
    response = client.post("/__demo/bugs", json={"active": ["unknown"]})
    assert response.status_code == 400


def test_sensitive_values_are_redacted():
    result = redact({"message": "password=hunter2 contact dev@example.com token:abc123"})
    assert "hunter2" not in json.dumps(result)
    assert "dev@example.com" not in json.dumps(result)
    assert "abc123" not in json.dumps(result)


def test_incident_payload_has_context_timestamp_and_bounded_logs(client):
    app = client.application
    handler = app.extensions["patchpilot_tail_handler"]
    for index in range(60):
        handler.lines.append(f"line {index}")
    with app.test_request_context("/orders?page=2&email=dev@example.com"):
        error = RuntimeError("token=secret-value for user dev@example.com")
        payload = build_incident(error, app, handler)
    assert payload["exceptionType"] == "RuntimeError"
    assert payload["request"]["path"] == "/orders"
    assert len(payload["logs"]) == 50
    assert "secret-value" not in json.dumps(payload)
    assert "dev@example.com" not in json.dumps(payload)
    assert payload["timestamp"]
