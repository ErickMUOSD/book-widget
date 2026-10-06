from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from sqlalchemy import event, inspect, text
from sqlalchemy.engine import Engine
from sqlmodel import Session, SQLModel, create_engine

from app.config import get_settings

engine: Engine | None = None


def init_engine(url: str | None = None) -> Engine:
    """Crea el engine global y las tablas. Se llama en el arranque y en los tests."""
    global engine
    url = url or get_settings().database_url
    kwargs: dict = {}
    if url.startswith("sqlite"):
        if ":memory:" not in url and url != "sqlite://":
            Path(url.split("///", 1)[1]).parent.mkdir(parents=True, exist_ok=True)
        kwargs["connect_args"] = {"check_same_thread": False}
        if ":memory:" in url or url == "sqlite://":
            from sqlalchemy.pool import StaticPool

            kwargs["poolclass"] = StaticPool
    engine = create_engine(url, **kwargs)
    if url.startswith("sqlite"):

        @event.listens_for(engine, "connect")
        def _pragmas(dbapi_conn, _):  # pragma: no cover - trivial
            cur = dbapi_conn.cursor()
            cur.execute("PRAGMA journal_mode=WAL")
            cur.execute("PRAGMA foreign_keys=ON")
            cur.close()

    from app import models  # noqa: F401  (registra las tablas)

    SQLModel.metadata.create_all(engine)
    _add_missing_columns(engine)
    return engine


# create_all no altera tablas existentes: columnas añadidas después de la primera versión.
_NEW_COLUMNS = {"sprite": {"subject": "TEXT NOT NULL DEFAULT ''"}}


def _add_missing_columns(engine: Engine) -> None:
    insp = inspect(engine)
    with engine.begin() as conn:
        for table, cols in _NEW_COLUMNS.items():
            have = {c["name"] for c in insp.get_columns(table)}
            for name, ddl in cols.items():
                if name not in have:
                    conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {name} {ddl}"))


def get_session() -> Iterator[Session]:
    assert engine is not None, "init_engine() no fue llamado"
    with Session(engine) as session:
        yield session


@contextmanager
def session_scope() -> Iterator[Session]:
    """Sesión para hilos en segundo plano (pregeneración, poller)."""
    assert engine is not None, "init_engine() no fue llamado"
    with Session(engine) as session:
        yield session
