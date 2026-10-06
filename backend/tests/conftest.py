import pytest
from fastapi.testclient import TestClient

from app import db
from app.config import get_settings
from app.services.claude_client import get_claude
from tests.fakes import FakeClaude

ADMIN = "admin-secret"


@pytest.fixture
def make_client(tmp_path, monkeypatch):
    monkeypatch.setenv("ADMIN_TOKEN", ADMIN)
    monkeypatch.setenv("USE_BATCH", "false")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "")
    monkeypatch.setenv("CLAUDE_BACKEND", "api")  # sin key: el arranque no lanza trabajos reales
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{tmp_path}/test.db")
    get_settings.cache_clear()
    db.init_engine()
    from app.main import app

    clients = []

    def _make(fake: FakeClaude | None = None, use_batch=False) -> tuple[TestClient, FakeClaude]:
        monkeypatch.setenv("USE_BATCH", "true" if use_batch else "false")
        get_settings.cache_clear()
        fake = fake or FakeClaude()
        app.dependency_overrides[get_claude] = lambda: fake
        # los hilos de fondo usan get_claude() directamente: lo apuntamos al falso
        monkeypatch.setattr("app.services.pregenerate.get_claude", lambda: fake)
        c = TestClient(app, headers={"X-Device-Token": ADMIN})
        clients.append(c)
        return c, fake

    yield _make
    app.dependency_overrides.clear()
    get_settings.cache_clear()
