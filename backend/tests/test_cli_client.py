import json
import subprocess

import pytest

from app.config import Settings, get_settings
from app.services import claude_client
from app.services.claude_client import SPOILERS_SCHEMA_STRICT, BookContext, ClaudeCLIClient, ClaudeClient
from tests.fakes import FakeClaude, good_sprite


def cli_result(structured, **extra) -> str:
    out = {"type": "result", "subtype": "success", "is_error": False, "structured_output": structured,
           "usage": {"input_tokens": 10, "output_tokens": 5}, "total_cost_usd": 0.001}
    return json.dumps({**out, **extra})


@pytest.fixture
def run(monkeypatch):
    """Sustituye subprocess.run: guarda cada llamada y devuelve la salida configurada en `state`."""
    calls: list[dict] = []
    state = {"stdout": cli_result({"chapters": []}), "returncode": 0, "raise": None}

    def fake_run(cmd, **kw):
        calls.append({"cmd": cmd, **kw})
        if state["raise"]:
            raise state["raise"]
        return subprocess.CompletedProcess(cmd, state["returncode"], stdout=state["stdout"], stderr="boom")

    monkeypatch.setattr(claude_client.subprocess, "run", fake_run)
    return calls, state


def cli(**kw) -> ClaudeCLIClient:
    return ClaudeCLIClient(Settings(_env_file=None, claude_model="claude-sonnet-5-5", **kw))


def test_cli_builds_command_and_returns_structured_output(run):
    calls, state = run
    items = [{"chapter": 1, "niebla": "a", "pista": "b", "peligro": "c"}]
    state["stdout"] = cli_result({"chapters": items})
    ctx = BookContext("El Hobbit", "Tolkien", "fantasia", chapters=[(1, "Una fiesta inesperada")])

    assert cli().spoilers_sync(ctx, 1, 1) == items
    cmd = calls[0]["cmd"]
    assert cmd[:2] == ["claude", "-p"]
    assert json.loads(cmd[cmd.index("--json-schema") + 1]) == SPOILERS_SCHEMA_STRICT
    system = cmd[cmd.index("--system-prompt") + 1]
    assert "Índice de capítulos" in system and "Una fiesta inesperada" in system
    assert cmd[cmd.index("--model") + 1] == "claude-sonnet-5-5"
    assert cmd[cmd.index("--tools") + 1] == ""
    assert "capítulos 1 al 1" in calls[0]["input"]


def test_cli_strips_api_key_from_subprocess_env(run, monkeypatch):
    calls, state = run
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-...")
    monkeypatch.setenv("CLAUDE_CODE_OAUTH_TOKEN", "oauth-token")
    state["stdout"] = cli_result({"known": True, "genre": "otro", "chapters": []})

    cli().index("Libro", "", "", None)
    env = calls[0]["env"]
    assert "ANTHROPIC_API_KEY" not in env and env["CLAUDE_CODE_OAUTH_TOKEN"] == "oauth-token"


@pytest.mark.parametrize("setup, exc", [
    ({"stdout": cli_result(None, is_error=True, result="Claude usage limit reached")}, RuntimeError),
    ({"stdout": cli_result(None, subtype="error_max_turns")}, RuntimeError),
    ({"stdout": "", "returncode": 1}, RuntimeError),
    ({"raise": subprocess.TimeoutExpired("claude", 300)}, RuntimeError),
    ({"stdout": cli_result(None)}, ValueError),  # éxito pero sin structured_output
    # el CLI se rindió validando el schema: salida inválida (reintentable), no fallo de infraestructura
    ({"stdout": cli_result(None, is_error=True, subtype="error_max_structured_output_retries"),
      "returncode": 1}, ValueError),
])
def test_cli_errors_raise(run, setup, exc):
    _, state = run
    state.update(setup)
    with pytest.raises(exc):
        cli().spoilers_sync(BookContext("X"), 1, 1)


def test_cli_sprite_uses_strict_schema(run):
    calls, state = run
    state["stdout"] = cli_result(good_sprite())
    cli().sprite(BookContext("X"), 1, None, [], ["#000000"])
    cmd = calls[0]["cmd"]
    assert cmd[cmd.index("--effort") + 1] == "medium"  # el sprite usa su propio esfuerzo
    pixels = json.loads(cmd[cmd.index("--json-schema") + 1])["properties"]["pixels"]
    assert pixels["minItems"] == pixels["maxItems"] == 32
    assert pixels["items"]["pattern"] == "^[.0123456789abcdef]{32}$"


def test_cli_has_no_batches():
    c = cli()
    assert c.supports_batch is False
    with pytest.raises(RuntimeError):
        c.spoilers_batch_submit(BookContext("X"), [(1, 1)])


def test_cli_ready_depends_on_binary():
    assert cli(claude_cli_path="no-existe-este-binario-claude").ready is False


def test_get_claude_picks_transport(monkeypatch):
    for backend, cls in (("cli", ClaudeCLIClient), ("api", ClaudeClient)):
        monkeypatch.setenv("CLAUDE_BACKEND", backend)
        get_settings.cache_clear()
        monkeypatch.setattr(claude_client, "_client", None)
        assert type(claude_client.get_claude()) is cls
    get_settings.cache_clear()


class FakeCLIClaude(FakeClaude):
    supports_batch = False


def test_cli_mode_pregenerates_sync_even_with_use_batch(make_client):
    c, fake = make_client(FakeCLIClaude(total=12), use_batch=True)
    bid = c.post("/books", json={"title": "Libro CLI"}).json()["id"]
    b = c.get(f"/books/{bid}").json()
    assert b["status"] == "ready" and b["spoilers_ready"] == 36
    assert fake.calls["submit"] == 0 and fake.calls["sync"] == 2
