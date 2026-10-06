"""Progreso de lectura, generación de sprites y estado que consumen los widgets."""
import logging

from sqlmodel import Session, select

from app.models import Book, ReadingState, Spoiler, Sprite, utcnow
from app.services.claude_client import ClaudeClient
from app.services.pregenerate import LEVELS, book_context, get_scene_row, scene_dict
from app.services.sprite import SpriteData, fallback_sprite, validate_sprite
from app.services.themes import theme_for

log = logging.getLogger("bookwidget.progress")


class BookNotReady(Exception):
    pass


def ensure_state(session: Session, book: Book) -> ReadingState:
    state = session.get(ReadingState, book.id)
    if state is None:
        has_active = session.exec(select(ReadingState).where(ReadingState.is_active == True)).first()  # noqa: E712
        state = ReadingState(book_id=book.id, is_active=has_active is None)
        session.add(state)
        session.commit()
        session.refresh(state)
    return state


def caption_for(session: Session, book: Book, state: ReadingState) -> str | None:
    nxt = state.current_chapter + 1
    if nxt > book.total_chapters:
        return None
    row = session.exec(
        select(Spoiler).where(
            Spoiler.book_id == book.id, Spoiler.chapter_number == nxt, Spoiler.level == state.spoiler_level
        )
    ).first()
    return row.caption if row else None


def latest_sprite(session: Session, book_id: int, chapter: int | None = None) -> Sprite | None:
    q = select(Sprite).where(Sprite.book_id == book_id)
    if chapter is not None:
        q = q.where(Sprite.chapter_number == chapter)
    return session.exec(q.order_by(Sprite.id.desc())).first()


def to_data(sp: Sprite) -> SpriteData:
    return SpriteData.from_json(sp.name, sp.palette_json, sp.pixels_json, sp.frames_json)


def generate_sprite(session: Session, claude: ClaudeClient, book: Book, chapter: int) -> Sprite:
    ctx = book_context(session, book)
    theme = theme_for(book.genre)
    scene = scene_dict(get_scene_row(session, book.id, chapter + 1))
    used = [
        s for s in session.exec(select(Sprite.subject).where(Sprite.book_id == book.id).order_by(Sprite.id)).all()
        if s
    ]
    used = list(dict.fromkeys(used))[-12:]  # sin duplicados, los más recientes
    data: SpriteData | None = None
    subject = ""
    err: str | None = None
    for attempt in range(2):
        try:
            raw = claude.sprite(ctx, chapter, scene, used, theme["base_palette"], err)
            data = validate_sprite(raw)
            subject = str(raw.get("subject") or "").strip()[:80]
            break
        except ValueError as e:  # SpriteError, JSON truncado o fuera de schema: vale la pena reintentar
            err = str(e)
            log.warning("sprite inválido (intento %s): %s", attempt + 1, e)
        except Exception as e:  # noqa: BLE001 - red, cuota, sin credenciales...
            log.warning("generación de sprite falló: %s", e)
            break
    fallback = data is None
    if data is None:
        data = fallback_sprite()
    palette, rows, frames = data.to_json()
    sp = Sprite(
        book_id=book.id, chapter_number=chapter, name=data.name, subject=subject, palette_json=palette,
        pixels_json=rows, frames_json=frames, fallback=fallback,
    )
    session.add(sp)
    session.commit()
    session.refresh(sp)
    return sp


def set_progress(
    session: Session, claude: ClaudeClient, book: Book, chapter: int, level: str | None = None
) -> ReadingState:
    if book.status != "ready":
        raise BookNotReady(book.status)
    if not (0 <= chapter <= book.total_chapters):
        raise ValueError(f"el capítulo debe estar entre 0 y {book.total_chapters}")
    if level is not None and level not in LEVELS:
        raise ValueError(f"nivel inválido: {level}")
    state = ensure_state(session, book)
    changed = chapter != state.current_chapter
    state.current_chapter = chapter
    if level:
        state.spoiler_level = level
    if changed:
        state.sealed = True
    state.updated_at = utcnow()
    session.add(state)
    session.commit()
    if changed or latest_sprite(session, book.id, chapter) is None:
        generate_sprite(session, claude, book, chapter)
    session.refresh(state)
    return state


def widget_state(session: Session, book: Book) -> dict:
    state = ensure_state(session, book)
    sp = latest_sprite(session, book.id, state.current_chapter) or latest_sprite(session, book.id)
    finished = book.status == "ready" and state.current_chapter >= book.total_chapters
    return {
        "book_id": book.id,
        "title": book.title,
        "author": book.author,
        "genre": book.genre,
        "theme": theme_for(book.genre),
        "status": book.status,
        "status_detail": book.status_detail,
        "current_chapter": state.current_chapter,
        "total_chapters": book.total_chapters,
        "next_chapter": None if finished or not book.total_chapters else state.current_chapter + 1,
        "finished": finished,
        "spoiler_level": state.spoiler_level,
        "sealed": state.sealed,
        "is_active": state.is_active,
        "caption": caption_for(session, book, state) if book.status == "ready" else None,
        "sprite": None if sp is None else {
            "id": sp.id,
            "name": sp.name,
            "subject": sp.subject,
            "chapter": sp.chapter_number,
            "frame_count": to_data(sp).frame_count,
            "frame_urls": [f"/sprites/{sp.id}/{i}.png" for i in range(to_data(sp).frame_count)],
            "fallback": sp.fallback,
        },
        "updated_at": state.updated_at.isoformat(),
    }
