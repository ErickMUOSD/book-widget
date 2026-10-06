from datetime import datetime, timezone

from sqlalchemy import UniqueConstraint
from sqlmodel import Field, SQLModel


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Book(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    title: str
    author: str = ""
    # título+autor normalizados: caché global por libro
    key: str = Field(index=True, unique=True)
    genre: str = "otro"
    synopsis: str = ""
    total_chapters: int = 0
    # pending | ready | unknown | error
    status: str = "pending"
    status_detail: str = ""
    batch_id: str | None = None
    created_at: datetime = Field(default_factory=utcnow)


class Chapter(SQLModel, table=True):
    __table_args__ = (UniqueConstraint("book_id", "number"),)
    id: int | None = Field(default=None, primary_key=True)
    book_id: int = Field(foreign_key="book.id", index=True)
    number: int
    title: str = ""


class Spoiler(SQLModel, table=True):
    """Pre-spoiler de *ese* capítulo (se muestra cuando el lector termina el anterior)."""

    __table_args__ = (UniqueConstraint("book_id", "chapter_number", "level"),)
    id: int | None = Field(default=None, primary_key=True)
    book_id: int = Field(foreign_key="book.id", index=True)
    chapter_number: int
    level: str  # niebla | pista | peligro
    caption: str


class ChapterScene(SQLModel, table=True):
    """Ficha visual de *ese* capítulo (pregenerada con los spoilers): de aquí sale el sujeto del pixel art."""

    __table_args__ = (UniqueConstraint("book_id", "chapter_number"),)
    id: int | None = Field(default=None, primary_key=True)
    book_id: int = Field(foreign_key="book.id", index=True)
    chapter_number: int
    nudo: str = ""
    desenlace: str = ""  # solo para el dibujante: nunca se muestra en el widget
    elements_json: str = "[]"  # [{"tipo", "nombre", "detalle"}, ...]


class ReadingState(SQLModel, table=True):
    book_id: int = Field(foreign_key="book.id", primary_key=True)
    current_chapter: int = 0
    spoiler_level: str = "pista"
    sealed: bool = True
    is_active: bool = False
    updated_at: datetime = Field(default_factory=utcnow)


class Sprite(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    book_id: int = Field(foreign_key="book.id", index=True)
    chapter_number: int
    name: str
    subject: str = ""  # elemento del capítulo que se dibujó (evita repetirlo)
    palette_json: str
    pixels_json: str  # lista de 32 strings
    frames_json: str  # lista de diffs [[x, y, idx], ...]
    fallback: bool = False
    created_at: datetime = Field(default_factory=utcnow)


class Device(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    name: str
    token_hash: str = Field(index=True, unique=True)
    created_at: datetime = Field(default_factory=utcnow)
