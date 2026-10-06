from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Response
from sqlmodel import Session, select

from app.auth import require_device
from app.db import get_session
from app.models import Book, Chapter, ChapterScene, ReadingState, Spoiler, Sprite
from app.schemas import BookCreate, BookRetry, CaptionIn, SceneIn
from app.services.claude_client import ClaudeClient, get_claude
from app.services.pregenerate import (
    LEVELS, MAX_WORDS, normalize_key, prepare_book, save_scene, scene_dict,
)
from app.services.progress import ensure_state
from app.services.themes import theme_for

router = APIRouter(dependencies=[Depends(require_device)])


def get_book_or_404(book_id: int, session: Session = Depends(get_session)) -> Book:
    book = session.get(Book, book_id)
    if book is None:
        raise HTTPException(404, "Libro no encontrado")
    return book


def book_out(session: Session, book: Book) -> dict:
    state = ensure_state(session, book)
    return {
        "id": book.id, "title": book.title, "author": book.author, "genre": book.genre,
        "theme": theme_for(book.genre), "synopsis": book.synopsis,
        "total_chapters": book.total_chapters, "status": book.status, "status_detail": book.status_detail,
        "is_active": state.is_active, "current_chapter": state.current_chapter,
        "spoiler_level": state.spoiler_level, "sealed": state.sealed,
    }


@router.post("/books", status_code=201)
def create_book(
    body: BookCreate, background: BackgroundTasks, response: Response,
    session: Session = Depends(get_session), claude: ClaudeClient = Depends(get_claude),
):
    key = normalize_key(body.title, body.author)
    book = session.exec(select(Book).where(Book.key == key)).first()
    if book is not None:
        # Caché global: el libro ya existe y no se vuelve a pregenerar.
        response.status_code = 200
        needs_info = book.status in ("unknown", "error") and (body.synopsis or body.total_chapters)
        if needs_info:
            book.synopsis = body.synopsis or book.synopsis
            book.total_chapters = body.total_chapters or book.total_chapters
            book.status, book.status_detail = "pending", ""
            session.add(book)
            session.commit()
            background.add_task(prepare_book, book.id, claude)
        return book_out(session, book)
    book = Book(
        title=body.title.strip(), author=body.author.strip(), key=key,
        synopsis=body.synopsis.strip(), total_chapters=body.total_chapters or 0,
    )
    session.add(book)
    session.commit()
    session.refresh(book)
    ensure_state(session, book)
    background.add_task(prepare_book, book.id, claude)
    return book_out(session, book)


@router.get("/books")
def list_books(session: Session = Depends(get_session)):
    return [book_out(session, b) for b in session.exec(select(Book).order_by(Book.id.desc())).all()]


@router.get("/books/{book_id}")
def get_book(book: Book = Depends(get_book_or_404), session: Session = Depends(get_session)):
    chapters = session.exec(select(Chapter).where(Chapter.book_id == book.id).order_by(Chapter.number)).all()
    spoiler_count = len(session.exec(select(Spoiler.id).where(Spoiler.book_id == book.id)).all())
    return {
        **book_out(session, book),
        "chapters": [{"number": c.number, "title": c.title} for c in chapters],
        "spoilers_ready": spoiler_count,
        "spoilers_expected": book.total_chapters * len(LEVELS),
    }


@router.post("/books/{book_id}/retry")
def retry_book(
    background: BackgroundTasks, body: BookRetry | None = None,
    book: Book = Depends(get_book_or_404), session: Session = Depends(get_session),
    claude: ClaudeClient = Depends(get_claude),
):
    if body:
        book.synopsis = body.synopsis if body.synopsis is not None else book.synopsis
        book.total_chapters = body.total_chapters or book.total_chapters
    book.status, book.status_detail = "pending", ""
    session.add(book)
    session.commit()
    background.add_task(prepare_book, book.id, claude)
    return book_out(session, book)


@router.post("/books/{book_id}/activate")
def activate_book(book: Book = Depends(get_book_or_404), session: Session = Depends(get_session)):
    for st in session.exec(select(ReadingState).where(ReadingState.is_active == True)).all():  # noqa: E712
        st.is_active = False
        session.add(st)
    state = ensure_state(session, book)
    state.is_active = True
    session.add(state)
    session.commit()
    return book_out(session, book)


@router.delete("/books/{book_id}", status_code=204)
def delete_book(book: Book = Depends(get_book_or_404), session: Session = Depends(get_session)):
    for model in (Spoiler, ChapterScene, Chapter, Sprite):
        for row in session.exec(select(model).where(model.book_id == book.id)).all():
            session.delete(row)
    state = session.get(ReadingState, book.id)
    if state:
        session.delete(state)
    session.flush()  # sin relaciones declaradas, SQLAlchemy no ordena los DELETE por FK
    session.delete(book)
    session.commit()


@router.get("/books/{book_id}/spoilers")
def list_spoilers(book: Book = Depends(get_book_or_404), session: Session = Depends(get_session)):
    rows = session.exec(
        select(Spoiler).where(Spoiler.book_id == book.id).order_by(Spoiler.chapter_number, Spoiler.id)
    ).all()
    by_ch: dict[int, dict] = {}
    for r in rows:
        by_ch.setdefault(r.chapter_number, {"chapter": r.chapter_number})[r.level] = r.caption
    for sc in session.exec(select(ChapterScene).where(ChapterScene.book_id == book.id)).all():
        if sc.chapter_number in by_ch:
            by_ch[sc.chapter_number]["escena"] = scene_dict(sc)
    return list(by_ch.values())


@router.put("/books/{book_id}/scenes/{chapter}")
def edit_scene(
    chapter: int, body: SceneIn,
    book: Book = Depends(get_book_or_404), session: Session = Depends(get_session),
):
    if not (1 <= chapter <= book.total_chapters):
        raise HTTPException(404, "Capítulo inexistente")
    row = save_scene(session, book.id, chapter, body.model_dump())
    if row is None:
        raise HTTPException(422, "La ficha necesita al menos un elemento con nombre")
    session.commit()
    return {"chapter": chapter, "escena": scene_dict(row)}


@router.put("/books/{book_id}/spoilers/{chapter}/{level}")
def edit_spoiler(
    chapter: int, level: str, body: CaptionIn,
    book: Book = Depends(get_book_or_404), session: Session = Depends(get_session),
):
    if level not in LEVELS or not (1 <= chapter <= book.total_chapters):
        raise HTTPException(404, "Capítulo o nivel inexistente")
    if len(body.caption.split()) > MAX_WORDS:
        raise HTTPException(422, f"La leyenda no puede superar {MAX_WORDS} palabras")
    row = session.exec(
        select(Spoiler).where(
            Spoiler.book_id == book.id, Spoiler.chapter_number == chapter, Spoiler.level == level
        )
    ).first() or Spoiler(book_id=book.id, chapter_number=chapter, level=level, caption="")
    row.caption = body.caption.strip()
    session.add(row)
    session.commit()
    return {"chapter": chapter, "level": level, "caption": row.caption}


@router.get("/books/{book_id}/sprites")
def list_sprites(book: Book = Depends(get_book_or_404), session: Session = Depends(get_session)):
    from app.services.progress import to_data

    rows = session.exec(select(Sprite).where(Sprite.book_id == book.id).order_by(Sprite.id.desc())).all()
    return [
        {
            "id": r.id, "name": r.name, "subject": r.subject, "chapter": r.chapter_number, "fallback": r.fallback,
            "created_at": r.created_at.isoformat(), "frame_count": to_data(r).frame_count,
            "frame_urls": [f"/sprites/{r.id}/{i}.png" for i in range(to_data(r).frame_count)],
        }
        for r in rows
    ]
