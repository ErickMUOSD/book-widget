"""Servidor de DESARROLLO sin API key: usa un Claude falso (datos de ejemplo).
Sirve para probar la app Android y la web sin gastar tokens.

    ADMIN_TOKEN=dev uv run uvicorn dev_fake_server:app --port 8000
"""
from app.main import app
from app.services import pregenerate
from app.services.claude_client import get_claude
from tests.fakes import FakeClaude

_fake = FakeClaude(total=12, genre="fantasia")
app.dependency_overrides[get_claude] = lambda: _fake
pregenerate.get_claude = lambda: _fake
