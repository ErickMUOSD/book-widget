"""Servidor de DESARROLLO sin API key: usa un Claude falso (datos de ejemplo).
Sirve para probar la app Android y la web sin gastar tokens.

    ADMIN_TOKEN=dev uv run uvicorn dev_fake_server:app --port 8000
"""
import os



# Servidor offline: sin fuentes externas (no toca la red) ni etapa B; todo lo resuelve el Claude falso.
os.environ.setdefault("BOOK_SOURCES", "")
os.environ.setdefault("GROUND_SPOILERS", "false")

from app.main import app  # noqa: E402
from app.services import pregenerate  # noqa: E402
from app.services.claude_client import get_claude  # noqa: E402
from tests.fakes import FakeClaude  # noqa: E402

_fake = FakeClaude(total=12, genre="fantasia")
app.dependency_overrides[get_claude] = lambda: _fake
pregenerate.get_claude = lambda: _fake
