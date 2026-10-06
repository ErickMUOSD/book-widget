from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlmodel import Session, select

from app.auth import hash_token, new_token, require_admin, require_device
from app.db import get_session
from app.models import Book, Device, ReadingState, Sprite
from app.routers.books import get_book_or_404
from app.schemas import DeviceCreate, ProgressIn, StatePatch
from app.services.card import build_card
from app.services.claude_client import ClaudeClient, get_claude
from app.services.pregenerate import LEVELS
from app.services.progress import (
    BookNotReady, caption_for, ensure_state, generate_sprite, latest_sprite, set_progress, to_data, widget_state,
)
from app.services.render import sprite_png
from app.services.themes import THEMES, theme_for

router = APIRouter(dependencies=[Depends(require_device)])
admin_router = APIRouter(dependencies=[Depends(require_admin)])


def _apply(session, claude, book, chapter, level=None) -> dict:
    try:
        set_progress(session, claude, book, chapter, level)
    except BookNotReady:
        raise HTTPException(409, f"El libro aún no está listo (estado: {book.status})")
    except ValueError as e:
        raise HTTPException(422, str(e))
    return widget_state(session, book)


@router.put("/books/{book_id}/progress")
def put_progress(
    body: ProgressIn, book: Book = Depends(get_book_or_404),
    session: Session = Depends(get_session), claude: ClaudeClient = Depends(get_claude),
):
    return _apply(session, claude, book, body.current_chapter, body.spoiler_level)


@router.post("/books/{book_id}/advance")
def advance(
    book: Book = Depends(get_book_or_404),
    session: Session = Depends(get_session), claude: ClaudeClient = Depends(get_claude),
):
    """Botón «Terminé ✓»: capítulo + 1 (sin pasar del último)."""
    state = ensure_state(session, book)
    return _apply(session, claude, book, min(state.current_chapter + 1, book.total_chapters))


@router.patch("/books/{book_id}/state")
def patch_state(body: StatePatch, book: Book = Depends(get_book_or_404), session: Session = Depends(get_session)):
    """Romper/volver a poner el sello y cambiar el nivel de spoiler. No llama a Claude."""
    state = ensure_state(session, book)
    if body.spoiler_level is not None:
        if body.spoiler_level not in LEVELS:
            raise HTTPException(422, f"nivel inválido: {body.spoiler_level}")
        state.spoiler_level = body.spoiler_level
    if body.sealed is not None:
        state.sealed = body.sealed
    session.add(state)
    session.commit()
    return widget_state(session, book)


@router.get("/books/{book_id}/widget")
def get_widget(book: Book = Depends(get_book_or_404), session: Session = Depends(get_session)):
    return widget_state(session, book)


@router.get("/widget")
def get_active_widget(session: Session = Depends(get_session)):
    state = session.exec(select(ReadingState).where(ReadingState.is_active == True)).first()  # noqa: E712
    book = session.get(Book, state.book_id) if state else None
    if book is None:
        raise HTTPException(404, "No hay un libro activo")
    return widget_state(session, book)


@router.post("/books/{book_id}/sprite/regenerate")
def regenerate_sprite(
    book: Book = Depends(get_book_or_404),
    session: Session = Depends(get_session), claude: ClaudeClient = Depends(get_claude),
):
    if book.status != "ready":
        raise HTTPException(409, f"El libro aún no está listo (estado: {book.status})")
    state = ensure_state(session, book)
    generate_sprite(session, claude, book, state.current_chapter)
    return widget_state(session, book)


@router.get("/sprites/{sprite_id}/{frame}.png")
def sprite_frame(sprite_id: int, frame: int, scale: int = Query(8, ge=1, le=16), session: Session = Depends(get_session)):
    sp = session.get(Sprite, sprite_id)
    if sp is None:
        raise HTTPException(404, "Sprite no encontrado")
    try:
        png = sprite_png(to_data(sp), frame, scale)
    except IndexError:
        raise HTTPException(404, "Frame inexistente")
    return Response(png, media_type="image/png", headers={"Cache-Control": "public, max-age=31536000, immutable"})


@router.get("/books/{book_id}/share-card.png")
def share_card(
    sealed: bool = True, book: Book = Depends(get_book_or_404), session: Session = Depends(get_session)
):
    state = ensure_state(session, book)
    sp = latest_sprite(session, book.id, state.current_chapter) or latest_sprite(session, book.id)
    cap = caption_for(session, book, state)
    nxt = state.current_chapter + 1
    label = f"Capitulo {state.current_chapter} > {nxt}" if nxt <= book.total_chapters else "Libro terminado"
    png = build_card(
        theme=theme_for(book.genre), book_title=book.title, chapter_label=label,
        sprite=to_data(sp) if sp else None, caption=cap, sealed=sealed,
    )
    return Response(png, media_type="image/png")


@router.get("/themes")
def themes():
    return {k: {"id": k, **v} for k, v in THEMES.items()}


# ---------- dispositivos (solo token maestro) ----------
@admin_router.get("/devices")
def list_devices(session: Session = Depends(get_session)):
    return [{"id": d.id, "name": d.name, "created_at": d.created_at.isoformat()} for d in session.exec(select(Device)).all()]


@admin_router.post("/devices", status_code=201)
def create_device(body: DeviceCreate, session: Session = Depends(get_session)):
    token = new_token()
    d = Device(name=body.name.strip(), token_hash=hash_token(token))
    session.add(d)
    session.commit()
    session.refresh(d)
    return {"id": d.id, "name": d.name, "token": token}  # el token solo se muestra ahora


@admin_router.delete("/devices/{device_id}", status_code=204)
def delete_device(device_id: int, session: Session = Depends(get_session)):
    d = session.get(Device, device_id)
    if d is None:
        raise HTTPException(404, "Dispositivo no encontrado")
    session.delete(d)
    session.commit()
