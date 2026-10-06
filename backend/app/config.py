from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # "cli" = CLI `claude -p` con la suscripción (login o CLAUDE_CODE_OAUTH_TOKEN), sin API key.
    # "api" = SDK anthropic con ANTHROPIC_API_KEY (permite Batches API y prompt caching).
    claude_backend: str = "cli"
    claude_cli_path: str = "claude"
    claude_cli_timeout: int = 300
    # --effort del CLI (low|medium|high|xhigh|max; vacío = el del CLI). Medido con un sprite: el esfuerzo por
    # defecto del CLI tardó ~3,5 min y ~29k tokens; "low" ~30-50 s y ~4-6k tokens.
    claude_cli_effort: str = "low"
    # Esfuerzo solo para el pixel art (más detalle y sombreado); vacío = claude_cli_effort.
    claude_cli_sprite_effort: str = "medium"
    anthropic_api_key: str = ""
    claude_model: str = "claude-sonnet-5-5"
    database_url: str = "sqlite:///./data/bookwidget.db"
    # Token maestro: único que puede gestionar dispositivos (lo usa el BFF web).
    admin_token: str = ""
    # Pregeneración con Message Batches API (50 % más barata, asíncrona). Solo en modo "api".
    use_batch: bool = True
    batch_poll_seconds: int = 30
    chapters_per_block: int = 10
    language: str = "español"


@lru_cache
def get_settings() -> Settings:
    return Settings()
