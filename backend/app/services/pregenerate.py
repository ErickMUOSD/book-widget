"""Alta de un libro: índice + pregeneración de los 3 niveles de pre-spoiler por capítulo.

Optimización de tokens:
  1. una sola llamada de índice (capítulos + género);
  2. bloques de N capítulos por llamada, salida estructurada;
  3. el prefijo (reglas + contexto del libro) se cachea con cache_control;
  4. modo Batches API (-50 %, solo con API key): se envía un batch y un poller recoge los resultados;
  5. caché global por libro (título+autor normalizados): nunca se repite la pregeneración;
  6. validación ≤50 palabras y reintento solo de lo que falta.
Cada capítulo trae además su ficha visual (nudo, desenlace, elementos dibujables) para el pixel art;
si falta no se reintenta: el sprite tiene un plan B."""
import json
import logging
import re
import unicodedata

from sqlmodel import Session, select

from app.config import get_settings
from app.db import session_scope
from app.models import Book, Chapter, ChapterScene, Spoiler
from app.services.claude_client import ELEMENT_TYPES, BookContext, ClaudeClient, get_claude
from app.services.themes import normalize_genre

log = logging.getLogger("bookwidget.pregenerate")
LEVELS = ("niebla", "pista", "peligro")
MAX_WORDS = 50


def normalize_key(title: str, author: str = "") -> str:
    def norm(s: str) -> str:
        s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
        return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()

    return f"{norm(title)}|{norm(author)}"


def book_context(session: Session, book: Book) -> BookContext:
    chapters = session.exec(select(Chapter).where(Chapter.book_id == book.id).order_by(Chapter.number)).all()
    return BookContext(book.title, book.author, book.genre, book.synopsis, [(c.number, c.title) for c in chapters])


def _blocks(total: int, size: int) -> list[tuple[int, int]]:
    return [(a, min(a + size - 1, total)) for a in range(1, total + 1, size)]


def _existing(session: Session, book_id: int) -> set[tuple[int, str]]:
    rows = session.exec(select(Spoiler.chapter_number, Spoiler.level).where(Spoiler.book_id == book_id)).all()
    return {(c, l) for c, l in rows}


def _save_items(
    session: Session, book: Book, items: list[dict], wanted: set[int], force_truncate: bool = False
) -> None:
    have = _existing(session, book.id)
    for it in items or []:
        ch = it.get("chapter")
        if not isinstance(ch, int) or ch not in wanted:
            continue
        for level in LEVELS:
            text = str(it.get(level) or "").strip()
            if not text or (ch, level) in have:
                continue
            words = text.split()
            if len(words) > MAX_WORDS:
                if not force_truncate:
                    continue
                text = " ".join(words[: MAX_WORDS - 2]) + "…"
            session.add(Spoiler(book_id=book.id, chapter_number=ch, level=level, caption=text))
            have.add((ch, level))
        save_scene(session, book.id, ch, it.get("escena"))
    session.commit()


def clean_scene(raw) -> dict | None:
    """Normaliza una ficha visual; None si no sirve (sin elementos)."""
    if not isinstance(raw, dict):
        return None
    elements = []
    for e in raw.get("elementos") or []:
        if not isinstance(e, dict) or not str(e.get("nombre") or "").strip():
            continue
        tipo = str(e.get("tipo") or "objeto").strip().lower()
        elements.append({
            "tipo": tipo if tipo in ELEMENT_TYPES else "objeto",
            "nombre": str(e["nombre"]).strip()[:80],
            "detalle": str(e.get("detalle") or "").strip()[:200],
        })
    if not elements:
        return None
    return {
        "nudo": str(raw.get("nudo") or "").strip()[:400],
        "desenlace": str(raw.get("desenlace") or "").strip()[:400],
        "elementos": elements[:6],
    }


def save_scene(session: Session, book_id: int, chapter: int, raw) -> ChapterScene | None:
    """Crea o reemplaza la ficha del capítulo (sin commit). Ignora fichas vacías."""
    scene = clean_scene(raw)
    if scene is None:
        return None
    row = get_scene_row(session, book_id, chapter) or ChapterScene(book_id=book_id, chapter_number=chapter)
    row.nudo, row.desenlace = scene["nudo"], scene["desenlace"]
    row.elements_json = json.dumps(scene["elementos"], ensure_ascii=False)
    session.add(row)
    return row


def get_scene_row(session: Session, book_id: int, chapter: int) -> ChapterScene | None:
    return session.exec(
        select(ChapterScene).where(ChapterScene.book_id == book_id, ChapterScene.chapter_number == chapter)
    ).first()


def scene_dict(row: ChapterScene | None) -> dict | None:
    if row is None:
        return None
    return {"nudo": row.nudo, "desenlace": row.desenlace, "elementos": json.loads(row.elements_json)}


def _missing_chapters(session: Session, book: Book) -> list[int]:
    have = _existing(session, book.id)
    return [n for n in range(1, book.total_chapters + 1) if any((n, l) not in have for l in LEVELS)]


def _ranges(nums: list[int]) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    for n in nums:
        if out and n == out[-1][1] + 1:
            out[-1] = (out[-1][0], n)
        else:
            out.append((n, n))
    return out


def _finalize(session: Session, book: Book, claude: ClaudeClient) -> None:
    """Reintenta solo los capítulos incompletos y marca el libro como listo (o con error)."""
    missing = _missing_chapters(session, book)
    if missing:
        ctx = book_context(session, book)
        for a, b in _ranges(missing):
            try:
                items = claude.spoilers_sync(ctx, a, b)
                _save_items(session, book, items, set(range(a, b + 1)), force_truncate=True)
            except Exception as e:  # noqa: BLE001
                log.warning("reintento de spoilers %s-%s falló: %s", a, b, e)
        missing = _missing_chapters(session, book)
    book.batch_id = None
    if missing:
        book.status = "error"
        book.status_detail = f"Faltan pre-spoilers de los capítulos: {missing[:20]}"
    else:
        book.status, book.status_detail = "ready", ""
    session.add(book)
    session.commit()


def prepare_book(book_id: int, claude: ClaudeClient | None = None) -> None:
    claude = claude or get_claude()
    with session_scope() as s:
        book = s.get(Book, book_id)
        if book is None:
            return
        try:
            _prepare(s, book, claude)
        except Exception as e:  # noqa: BLE001
            log.exception("prepare_book(%s) falló", book_id)
            s.rollback()
            book = s.get(Book, book_id)
            book.status, book.status_detail, book.batch_id = "error", str(e)[:300], None
            s.add(book)
            s.commit()


def _prepare(s: Session, book: Book, claude: ClaudeClient) -> None:
    settings = get_settings()
    hint = book.total_chapters or None  # si el usuario indicó el total, se respeta
    for model in (Spoiler, ChapterScene, Chapter):
        for row in s.exec(select(model).where(model.book_id == book.id)).all():
            s.delete(row)
    book.batch_id = None
    s.commit()

    res = claude.index(book.title, book.author, book.synopsis, hint)
    titles: dict[int, str] = {}
    for i, ch in enumerate(sorted(res.get("chapters") or [], key=lambda c: c.get("n", 0)), start=1):
        titles[i] = str(ch.get("title") or "").strip()

    if hint:
        total = hint
    elif res.get("known") and titles:
        total = len(titles)
    else:
        book.status = "unknown"
        book.status_detail = "No reconozco el libro: indica el número de capítulos y, si puedes, una sinopsis."
        s.add(book)
        s.commit()
        return

    book.genre = normalize_genre(res.get("genre"))
    book.total_chapters = total
    for n in range(1, total + 1):
        s.add(Chapter(book_id=book.id, number=n, title=titles.get(n, "")))
    book.status, book.status_detail = "pending", ""
    s.add(book)
    s.commit()

    ctx = book_context(s, book)
    blocks = _blocks(total, settings.chapters_per_block)
    if settings.use_batch and claude.supports_batch:
        book.batch_id = claude.spoilers_batch_submit(ctx, blocks)
        s.add(book)
        s.commit()  # lo cierra poll_batches()
        return
    for a, b in blocks:
        try:
            _save_items(s, book, claude.spoilers_sync(ctx, a, b), set(range(a, b + 1)))
        except Exception as e:  # noqa: BLE001
            log.warning("bloque %s-%s falló: %s", a, b, e)
    _finalize(s, book, claude)


def poll_batches(claude: ClaudeClient | None = None) -> None:
    """Recoge los batches terminados. Lo llama el poller de fondo (o los tests)."""
    claude = claude or get_claude()
    with session_scope() as s:
        books = s.exec(select(Book).where(Book.status == "pending", Book.batch_id != None)).all()  # noqa: E711
        for book in books:
            try:
                if claude.batch_status(book.batch_id) != "ended":
                    continue
                for cid, items in claude.spoilers_batch_collect(book.batch_id).items():
                    if not items:
                        continue
                    a, b = (int(x) for x in cid[1:].split("-"))
                    _save_items(s, book, items, set(range(a, b + 1)))
                _finalize(s, book, claude)
            except Exception:  # noqa: BLE001
                log.exception("poll de batch %s falló; se reintentará", book.batch_id)
                s.rollback()
