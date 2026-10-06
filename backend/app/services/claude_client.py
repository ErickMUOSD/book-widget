"""Cliente de Claude: prompts, salida estructurada (JSON schema), prompt caching y Batches API.

Todo el acceso a Claude vive aquí; el resto del backend usa estos métodos de alto nivel
(y los tests los sustituyen por un falso). Dos transportes:
  - ClaudeClient: SDK anthropic + ANTHROPIC_API_KEY (CLAUDE_BACKEND=api);
  - ClaudeCLIClient: CLI oficial `claude -p` con la suscripción, sin API key (CLAUDE_BACKEND=cli)."""
import json
import logging
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from string import Template

import anthropic

from app.config import Settings, get_settings
from app.services.sprite import INDEX_CHARS, MAX_COLORS, MAX_FRAME_DIFFS, SIZE, TRANSPARENT
from app.services.themes import GENRES

log = logging.getLogger("bookwidget.claude")
PROMPTS = Path(__file__).resolve().parent.parent / "prompts"


@dataclass
class BookContext:
    title: str
    author: str = ""
    genre: str = "otro"
    synopsis: str = ""
    chapters: list[tuple[int, str]] = field(default_factory=list)  # (número, título)

    def render(self) -> str:
        lines = [f"Libro: {self.title}"]
        if self.author:
            lines.append(f"Autor: {self.author}")
        lines.append(f"Género: {self.genre}")
        if self.synopsis:
            lines.append(f"Sinopsis aportada por el usuario: {self.synopsis}")
        lines.append("Índice de capítulos:")
        lines += [f"{n}. {t}".rstrip() for n, t in self.chapters]
        return "\n".join(lines)


def _prompt(name: str, **vars) -> str:
    return Template((PROMPTS / name).read_text(encoding="utf-8")).safe_substitute(**vars)


INDEX_SCHEMA = {
    "type": "object",
    "properties": {
        "known": {"type": "boolean"},
        "genre": {"type": "string", "enum": GENRES},
        "chapters": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"n": {"type": "integer"}, "title": {"type": "string"}},
                "required": ["n", "title"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["known", "genre", "chapters"],
    "additionalProperties": False,
}

ELEMENT_TYPES = ["objeto", "animal", "criatura", "personaje", "lugar"]

SCENE_SCHEMA = {
    "type": "object",
    "properties": {
        "nudo": {"type": "string"},
        "desenlace": {"type": "string"},
        "elementos": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "tipo": {"type": "string", "enum": ELEMENT_TYPES},
                    "nombre": {"type": "string"},
                    "detalle": {"type": "string"},
                },
                "required": ["tipo", "nombre", "detalle"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["nudo", "desenlace", "elementos"],
    "additionalProperties": False,
}


def _spoilers_schema(scene: dict) -> dict:
    return {
        "type": "object",
        "properties": {
            "chapters": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "chapter": {"type": "integer"},
                        "niebla": {"type": "string"},
                        "pista": {"type": "string"},
                        "peligro": {"type": "string"},
                        "escena": scene,
                    },
                    "required": ["chapter", "niebla", "pista", "peligro", "escena"],
                    "additionalProperties": False,
                },
            }
        },
        "required": ["chapters"],
        "additionalProperties": False,
    }


SPOILERS_SCHEMA = _spoilers_schema(SCENE_SCHEMA)
# Solo modo CLI (ver SPRITE_SCHEMA_STRICT): de 3 a 5 elementos por ficha.
SPOILERS_SCHEMA_STRICT = _spoilers_schema({
    **SCENE_SCHEMA,
    "properties": {
        **SCENE_SCHEMA["properties"],
        "elementos": {**SCENE_SCHEMA["properties"]["elementos"], "minItems": 3, "maxItems": 5},
    },
})

SPRITE_SCHEMA = {
    "type": "object",
    "properties": {
        "subject": {"type": "string"},
        "name": {"type": "string"},
        "palette": {"type": "array", "items": {"type": "string"}},
        "pixels": {"type": "array", "items": {"type": "string"}},
        "frames": {
            "type": "array",
            "items": {"type": "array", "items": {"type": "array", "items": {"type": "integer"}}},
        },
    },
    "required": ["subject", "name", "palette", "pixels", "frames"],
    "additionalProperties": False,
}

# Solo modo CLI: el modelo ve y respeta longitudes y patrones, así la cuadrícula sale de 32×32 a la primera.
# La ruta API conserva SPRITE_SCHEMA (no verificado que structured outputs admita estas restricciones).
SPRITE_SCHEMA_STRICT = {
    **SPRITE_SCHEMA,
    "properties": {
        **SPRITE_SCHEMA["properties"],
        "palette": {
            "type": "array", "minItems": 2, "maxItems": MAX_COLORS,
            "items": {"type": "string", "pattern": "^#[0-9a-fA-F]{6}$"},
        },
        "pixels": {
            "type": "array", "minItems": SIZE, "maxItems": SIZE,
            "items": {"type": "string", "pattern": f"^[{TRANSPARENT}{INDEX_CHARS}]{{{SIZE}}}$"},
        },
        "frames": {**SPRITE_SCHEMA["properties"]["frames"], "maxItems": MAX_FRAME_DIFFS},
    },
}


class ClaudeClient:
    supports_batch = True
    spoilers_schema = SPOILERS_SCHEMA
    sprite_schema = SPRITE_SCHEMA

    def __init__(self, settings: Settings | None = None):
        self.settings = settings or get_settings()
        self._client: anthropic.Anthropic | None = None

    @property
    def ready(self) -> bool:
        """Hay credenciales para llamar a Claude (el arranque solo lanza trabajos si es así)."""
        return bool(self.settings.anthropic_api_key)

    @property
    def client(self) -> anthropic.Anthropic:
        if self._client is None:
            if not self.settings.anthropic_api_key:
                raise RuntimeError("ANTHROPIC_API_KEY no está configurada")
            self._client = anthropic.Anthropic(api_key=self.settings.anthropic_api_key, max_retries=3)
        return self._client

    # ---------- construcción de peticiones ----------
    def _params(self, *, system: list[dict], user: str, schema: dict, max_tokens: int) -> dict:
        return {
            "model": self.settings.claude_model,
            "max_tokens": max_tokens,
            "system": system,
            "messages": [{"role": "user", "content": user}],
            "output_config": {"format": {"type": "json_schema", "schema": schema}},
        }

    def _call(self, params: dict, effort: str | None = None) -> dict:
        """`effort` solo lo usa el transporte CLI (--effort)."""
        msg = self.client.messages.create(**params)
        u = msg.usage
        log.info(
            "claude usage in=%s out=%s cache_read=%s cache_write=%s",
            u.input_tokens, u.output_tokens,
            getattr(u, "cache_read_input_tokens", 0), getattr(u, "cache_creation_input_tokens", 0),
        )
        return _json_from(msg)

    # ---------- alta del libro ----------
    def index(self, title: str, author: str, synopsis: str, total_hint: int | None) -> dict:
        system = [{"type": "text", "text": _prompt("toc.md", genres=", ".join(GENRES), language=self.settings.language)}]
        user = f"Libro: {title}\nAutor: {author or '(no indicado)'}"
        if total_hint:
            user += f"\nNúmero de capítulos indicado por el usuario: {total_hint}"
        if synopsis:
            user += f"\nSinopsis aportada por el usuario: {synopsis}"
        return self._call(self._params(system=system, user=user, schema=INDEX_SCHEMA, max_tokens=8000))

    # ---------- pre-spoilers ----------
    def _spoiler_params(self, ctx: BookContext, first: int, last: int) -> dict:
        # El prefijo (reglas + contexto del libro) lleva cache_control: los bloques siguientes
        # solo pagan la parte nueva (el rango de capítulos).
        system = [
            {"type": "text", "text": _prompt("spoilers.md", language=self.settings.language)},
            {"type": "text", "text": ctx.render(), "cache_control": {"type": "ephemeral"}},
        ]
        user = (
            f"Escribe los tres pre-spoilers y la ficha visual de los capítulos {first} al {last} (ambos incluidos)."
        )
        return self._params(system=system, user=user, schema=self.spoilers_schema, max_tokens=12000)

    def spoilers_sync(self, ctx: BookContext, first: int, last: int) -> list[dict]:
        return self._call(self._spoiler_params(ctx, first, last)).get("chapters", [])

    def spoilers_batch_submit(self, ctx: BookContext, blocks: list[tuple[int, int]]) -> str:
        reqs = [
            {"custom_id": f"c{a}-{b}", "params": self._spoiler_params(ctx, a, b)} for a, b in blocks
        ]
        return self.client.messages.batches.create(requests=reqs).id

    def batch_status(self, batch_id: str) -> str:
        """in_progress | canceling | ended"""
        return self.client.messages.batches.retrieve(batch_id).processing_status

    def spoilers_batch_collect(self, batch_id: str) -> dict[str, list[dict] | None]:
        out: dict[str, list[dict] | None] = {}
        for entry in self.client.messages.batches.results(batch_id):
            if entry.result.type == "succeeded":
                try:
                    out[entry.custom_id] = _json_from(entry.result.message).get("chapters", [])
                except (ValueError, KeyError):
                    out[entry.custom_id] = None
            else:
                out[entry.custom_id] = None
        return out

    # ---------- pixel art ----------
    def sprite(
        self, ctx: BookContext, chapter: int, scene: dict | None, used_subjects: list[str],
        base_palette: list[str], previous_error: str | None = None,
    ) -> dict:
        """Dibuja un elemento del capítulo SIGUIENTE a `chapter` (el último que el lector terminó),
        elegido de su ficha visual (`scene`, puede faltar) y distinto de `used_subjects`."""
        system = [{"type": "text", "text": _prompt("sprite.md")}]
        titles = dict(ctx.chapters)
        target = chapter + 1
        lines = [
            f"Libro: {ctx.title}" + (f" — {ctx.author}" if ctx.author else ""),
            f"Género: {ctx.genre}",
        ]
        if ctx.synopsis:
            lines.append(f"Sinopsis: {ctx.synopsis}")
        if target > len(ctx.chapters):
            lines.append("El lector terminó el libro: dibuja el elemento más icónico de toda la obra, en tono de celebración.")
        else:
            t = titles.get(target, "")
            lines.append(
                (f"El lector terminó el capítulo {chapter} y va a leer" if chapter else "El lector va a empezar con")
                + f" el capítulo {target}" + (f" («{t}»)" if t else "") + "."
            )
            if scene and scene.get("elementos"):
                lines.append(f"Ficha visual del capítulo {target}:")
                lines.append(f"- Nudo: {scene.get('nudo', '')}")
                lines.append(f"- Desenlace (solo para ti, no lo dibujes): {scene.get('desenlace', '')}")
                lines.append("- Elementos:")
                lines += [f"  · {e['tipo']}: {e['nombre']} — {e['detalle']}" for e in scene["elementos"]]
            else:
                lines.append(
                    f"No hay ficha: recuerda el nudo y el desenlace del capítulo {target} y elige de ahí un elemento"
                    " dibujable; si no lo conoces, algo atmosférico coherente con el título y la sinopsis."
                )
        if used_subjects:
            lines.append(f"Ya se dibujaron (elige otro distinto): {', '.join(used_subjects)}")
        lines.append(f"Paleta base del tema: {', '.join(base_palette)}")
        user = "\n".join(lines)
        if previous_error:
            user += f"\n\nTu intento anterior fue rechazado: {previous_error}. Corrígelo."
        return self._call(
            self._params(system=system, user=user, schema=self.sprite_schema, max_tokens=8000),
            effort=self.settings.claude_cli_sprite_effort,
        )


def _json_from(message) -> dict:
    if message.stop_reason == "max_tokens":
        raise ValueError("respuesta truncada por max_tokens")
    text = "".join(b.text for b in message.content if getattr(b, "type", "") == "text")
    return json.loads(text)


# El CLI antepone estas variables al login de la suscripción: un placeholder en .env rompería la llamada.
_API_ENV = ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")


class ClaudeCLIClient(ClaudeClient):
    """Mismos prompts y schemas, pero cada llamada ejecuta `claude -p` autenticado con la suscripción
    (login local del CLI o CLAUDE_CODE_OAUTH_TOKEN de `claude setup-token`). Sin Batches API ni
    cache_control: con suscripción no se paga por token, cuentan los límites de uso del plan."""

    supports_batch = False
    spoilers_schema = SPOILERS_SCHEMA_STRICT
    sprite_schema = SPRITE_SCHEMA_STRICT

    @property
    def ready(self) -> bool:
        return shutil.which(self.settings.claude_cli_path) is not None

    @property
    def client(self) -> anthropic.Anthropic:
        raise RuntimeError("la Batches API no está disponible con CLAUDE_BACKEND=cli")

    def _call(self, params: dict, effort: str | None = None) -> dict:
        cmd = [
            self.settings.claude_cli_path, "-p",
            "--output-format", "json",
            "--json-schema", json.dumps(params["output_config"]["format"]["schema"]),
            "--system-prompt", "\n\n".join(b["text"] for b in params["system"]),
            "--model", params["model"],
            "--tools", "",  # sin herramientas: solo texto -> JSON
            "--no-session-persistence",
            "--safe-mode",  # ignora CLAUDE.md, plugins, hooks y MCP del usuario
        ]
        effort = effort or self.settings.claude_cli_effort
        if effort:
            cmd += ["--effort", effort]
        env = {k: v for k, v in os.environ.items() if k not in _API_ENV}
        timeout = self.settings.claude_cli_timeout
        try:
            proc = subprocess.run(
                cmd, input=params["messages"][0]["content"], capture_output=True, text=True,
                timeout=timeout, cwd=tempfile.gettempdir(), env=env,
            )
        except subprocess.TimeoutExpired as e:
            raise RuntimeError(f"claude CLI no respondió en {timeout}s") from e
        try:
            out = json.loads(proc.stdout)
        except json.JSONDecodeError:
            detail = (proc.stderr or proc.stdout or "").strip()[-300:]
            raise RuntimeError(f"claude CLI falló (código {proc.returncode}): {detail}") from None
        if out.get("subtype") == "error_max_structured_output_retries":
            # Salida inválida (no infraestructura): el llamador puede reintentar como con un JSON malo.
            raise ValueError("la respuesta no cumplió el JSON schema tras varios intentos del CLI")
        if proc.returncode != 0 or out.get("is_error") or out.get("subtype") != "success":
            raise RuntimeError(f"claude CLI: {out.get('subtype')} {str(out.get('result') or '')[:300]}")
        u = out.get("usage") or {}
        log.info(
            "claude usage in=%s out=%s thinking=%s cache_read=%s cache_write=%s (cli, %.1fs, ~%s USD nocionales)",
            u.get("input_tokens"), u.get("output_tokens"),
            (u.get("output_tokens_details") or {}).get("thinking_tokens"),
            u.get("cache_read_input_tokens", 0), u.get("cache_creation_input_tokens", 0),
            (out.get("duration_ms") or 0) / 1000, out.get("total_cost_usd"),
        )
        data = out.get("structured_output")
        if not isinstance(data, dict):
            raise ValueError("el CLI no devolvió structured_output")
        return data


_client: ClaudeClient | None = None


def get_claude() -> ClaudeClient:
    """Dependencia FastAPI (los tests la sobrescriben)."""
    global _client
    if _client is None:
        _client = ClaudeCLIClient() if get_settings().claude_backend == "cli" else ClaudeClient()
    return _client
