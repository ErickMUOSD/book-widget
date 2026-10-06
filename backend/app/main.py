import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from sqlmodel import select

from app import db
from app.config import get_settings
from app.models import Book
from app.routers import books, progress
from app.services.claude_client import get_claude
from app.services.pregenerate import poll_batches, prepare_book

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("bookwidget")


async def _poller(seconds: int) -> None:
    while True:
        await asyncio.sleep(seconds)
        try:
            await asyncio.to_thread(poll_batches)
        except Exception:  # noqa: BLE001
            log.exception("poll_batches falló")


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    if db.engine is None:
        db.init_engine()
    tasks: list[asyncio.Task] = []
    claude = get_claude()
    if claude.ready:
        # Recuperación tras un reinicio: libros pendientes que nunca llegaron a enviar su batch.
        with db.session_scope() as s:
            orphans = [b.id for b in s.exec(select(Book).where(Book.status == "pending", Book.batch_id == None)).all()]  # noqa: E711
        for bid in orphans:
            tasks.append(asyncio.create_task(asyncio.to_thread(prepare_book, bid)))
        if settings.use_batch and claude.supports_batch:
            tasks.append(asyncio.create_task(_poller(settings.batch_poll_seconds)))
    yield
    for t in tasks:
        t.cancel()


app = FastAPI(title="Book Widget API", version="0.1.0", lifespan=lifespan)
app.include_router(books.router)
app.include_router(progress.router)
app.include_router(progress.admin_router)


@app.get("/health")
def health():
    return {"ok": True}
