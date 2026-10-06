FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
# CLI de Claude Code (CLAUDE_BACKEND=cli): se autentica con CLAUDE_CODE_OAUTH_TOKEN (`claude setup-token`).
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL https://claude.ai/install.sh | bash \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /srv
COPY pyproject.toml uv.lock ./
RUN uv sync --no-dev --no-install-project
COPY app ./app
ENV PATH="/srv/.venv/bin:/root/.local/bin:$PATH" DATABASE_URL="sqlite:////data/bookwidget.db" \
    DISABLE_AUTOUPDATER=1
VOLUME /data
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
