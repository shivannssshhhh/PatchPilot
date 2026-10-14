"""ShopLite, a deliberately small demo application for PatchPilot."""

from __future__ import annotations

import logging
import os
from typing import Any

from flask import Flask, jsonify, request

from .incidents import install_error_reporting

BUG_NAMES = {"pagination", "users", "discount", "inventory", "payments"}


def _configured_bugs() -> set[str]:
    values = os.environ.get("SHOPLITE_BUGS", "")
    return {value.strip().lower() for value in values.split(",") if value.strip()} & BUG_NAMES


def create_app(test_config: dict[str, Any] | None = None) -> Flask:
    app = Flask(__name__)
    app.config.update(ACTIVE_BUGS=_configured_bugs(), TESTING=False)
    app.config["DEMO_CONTROLS_ENABLED"] = os.environ.get("SHOPLITE_DEMO_CONTROLS", "true").lower() == "true"
    if test_config:
        app.config.update(test_config)
    app.config["ACTIVE_BUGS"] = set(app.config["ACTIVE_BUGS"]) & BUG_NAMES
    if not app.config.get("DISABLE_ERROR_REPORTING", False):
        install_error_reporting(app)

    @app.after_request
    def allow_local_dashboard(response):
        origin = request.headers.get("Origin")
        allowed_origins = {"http://localhost:5173", "http://127.0.0.1:5173"}
        configured_origin = os.environ.get("SHOPLITE_DASHBOARD_ORIGIN")
        if configured_origin:
            allowed_origins.add(configured_origin.rstrip("/"))
        if origin in allowed_origins:
            response.headers["Access-Control-Allow-Origin"] = origin
            response.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
            response.headers["Access-Control-Allow-Headers"] = "Content-Type"
            response.headers["Vary"] = "Origin"
        return response

    users = {
        1: {"id": 1, "name": "Asha Rao", "email": "asha@example.test"},
        2: {"id": 2, "name": "Dev Patel", "email": "dev@example.test"},
    }
    products = [
        {"id": "mug", "name": "FutureForge Mug", "price": 8.0},
        {"id": "notebook", "name": "Build Notebook", "price": 5.0},
        {"id": "sticker", "name": "PatchPilot Sticker", "price": 2.0},
    ]

    def broken(name: str) -> bool:
        return name in app.config["ACTIVE_BUGS"]

    @app.get("/health")
    def health():
        return jsonify(status="healthy", active_bugs=sorted(app.config["ACTIVE_BUGS"]))

    @app.get("/users/<int:user_id>")
    def get_user(user_id: int):
        user = users.get(user_id)
        if user is None:
            return jsonify(error="user not found"), 404
        if broken("users"):
            # Seeded typo: reproduces as a KeyError for a valid user.
            return jsonify(id=user["id"], name=user["naem"], email=user["email"])
        return jsonify(user)

    @app.get("/orders")
    def get_orders():
        page_value = request.args.get("page", "1")
        if broken("pagination"):
            # Seeded type error: a string offset is used for list slicing.
            start = page_value * 2
            return jsonify(items=products[start : start + 2], page=page_value)
        try:
            page = max(1, int(page_value))
        except ValueError:
            return jsonify(error="page must be a positive integer"), 400
        start = (page - 1) * 2
        return jsonify(items=products[start : start + 2], page=page)

    @app.post("/cart/discount")
    def apply_discount():
        payload = request.get_json(silent=True) or {}
        code = str(payload.get("code", "")).upper()
        subtotal = float(payload.get("subtotal", 0))
        discounts = {"FORGE10": 0.10, "WELCOME5": 0.05}
        if broken("discount"):
            # Seeded missing-key failure for a valid promotion code.
            rate = discounts["FORG10"]
        else:
            if code not in discounts:
                return jsonify(error="unknown discount code"), 400
            rate = discounts[code]
        return jsonify(subtotal=round(subtotal, 2), total=round(subtotal * (1 - rate), 2))

    @app.post("/inventory/sync")
    def sync_inventory():
        payload = request.get_json(silent=True) or {}
        items = payload.get("items", [])
        if not isinstance(items, list):
            return jsonify(error="items must be a list"), 400
        if broken("inventory"):
            # Seeded arithmetic failure when an empty inventory batch arrives.
            _ = 1 // len(items)
        return jsonify(status="synced", count=len(items))

    @app.post("/payments/charge")
    def charge_payment():
        payload = request.get_json(silent=True) or {}
        if broken("payments"):
            # Kept as a refusal demo: payment paths are always sensitive.
            raise RuntimeError("payment processor simulation failed")
        amount = float(payload.get("amount", 0))
        if amount <= 0:
            return jsonify(error="amount must be greater than zero"), 400
        return jsonify(status="charged", amount=round(amount, 2), reference="demo-charge")

    @app.route("/__demo/bugs", methods=["GET", "POST"])
    def demo_bugs():
        """Local-only demo control; do not expose this endpoint in a deployment."""
        if not app.config["DEMO_CONTROLS_ENABLED"]:
            return jsonify(error="demo controls are disabled"), 404
        if request.method == "GET":
            return jsonify(available=sorted(BUG_NAMES), active=sorted(app.config["ACTIVE_BUGS"]))
        payload = request.get_json(silent=True) or {}
        requested = payload.get("active", [])
        if not isinstance(requested, list) or any(name not in BUG_NAMES for name in requested):
            return jsonify(error="active must be a list of known bug names"), 400
        app.config["ACTIVE_BUGS"] = set(requested)
        app.logger.info("Demo bug switches changed: %s", sorted(requested))
        return jsonify(active=sorted(app.config["ACTIVE_BUGS"]))

    return app
