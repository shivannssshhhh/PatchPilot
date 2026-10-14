"""Redacted incident capture and best-effort delivery to PatchPilot."""

from __future__ import annotations

import json
import logging
import os
import re
import traceback
import urllib.error
import urllib.request
from collections import deque
from datetime import datetime, timezone
from typing import Any

from flask import Flask, jsonify, request

EMAIL_RE = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)
SECRET_RE = re.compile(
    r"(?i)(password|token|secret|api[_-]?key)\s*([=:])\s*([^\s,;&]+)"
)


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): redact(item) for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, str):
        value = SECRET_RE.sub(r"\1\2[REDACTED]", value)
        return EMAIL_RE.sub("[REDACTED-EMAIL]", value)
    return value


class TailLogHandler(logging.Handler):
    def __init__(self, capacity: int = 50):
        super().__init__()
        self.lines: deque[str] = deque(maxlen=capacity)

    def emit(self, record: logging.LogRecord) -> None:
        try:
            self.lines.append(self.format(record))
        except Exception:
            self.handleError(record)


def _tail_handler(app: Flask) -> TailLogHandler:
    handler = app.extensions.get("patchpilot_tail_handler")
    if handler is None:
        handler = TailLogHandler(50)
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
        app.logger.addHandler(handler)
        handler.setLevel(logging.INFO)
        app.extensions["patchpilot_tail_handler"] = handler
    return handler


def build_incident(error: Exception, app: Flask, handler: TailLogHandler) -> dict[str, Any]:
    context = {
        "method": request.method,
        "path": request.path,
        "params": request.values.to_dict(flat=True),
    }
    return redact(
        {
            "exceptionType": type(error).__name__,
            "message": str(error),
            "stackTrace": "".join(traceback.format_exception(type(error), error, error.__traceback__)),
            "request": context,
            "logs": list(handler.lines)[-50:],
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "appVersion": app.config.get("APP_VERSION", "0.1.0"),
        }
    )


def _send_incident(payload: dict[str, Any], app: Flask) -> None:
    endpoint = os.environ.get("PATCHPILOT_API_URL", "http://localhost:8000/api/incidents")
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(endpoint, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=1.0) as response:
            if response.status >= 400:
                app.logger.warning("PatchPilot incident endpoint returned HTTP %s", response.status)
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
        app.logger.warning("Could not deliver incident to PatchPilot API: %s", exc)


def install_error_reporting(app: Flask) -> None:
    handler = _tail_handler(app)

    @app.errorhandler(Exception)
    def report_unhandled_error(error: Exception):
        app.logger.exception("ShopLite request failed")
        payload = build_incident(error, app, handler)
        _send_incident(payload, app)
        return jsonify(error="internal server error", incident="captured"), 500
