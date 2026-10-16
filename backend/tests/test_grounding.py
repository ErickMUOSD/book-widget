"""Etapa B: cuando el índice vino de una fuente comprobada, los pre-spoilers se fundamentan en el
texto real (resúmenes). Con fallback a Claude (grounded=False) la etapa B se omite."""
from sqlmodel import Session, select

from app import db
from app.config import get_settings
from app.models import Chapter
from app.services import book_sources as bs
from app.services.book_sources import IndexResult, SourceRef
from tests.fakes import FakeClaude

HDR = {"X-Device-Token": "admin-secret"}


def _patch_source(monkeypatch, n: int, grounded: bool):
    chapters = [{"n": i, "title": f"Cap real {i}"} for i in range(1, n + 1)]
    ref = SourceRef("fake", {}) if grounded else None
    monkeypatch.setattr(
        bs, "resolve_index",
        lambda title, author, synopsis, hint, claude: IndexResult(True, "aventura", chapters, grounded, ref),
    )
    monkeypatch.setattr(bs, "chapter_texts", lambda ref: {i: f"Texto real {i}." for i in range(1, n + 1)})


def test_grounded_summarizes_and_feeds_spoilers(make_client, monkeypatch):
    monkeypatch.setenv("GROUND_SPOILERS", "true")
    _patch_source(monkeypatch, n=5, grounded=True)
    client, fake = make_client(FakeClaude())

    # hint grande: la fuente (5 capítulos) debe ganar.
    r = client.post("/books", json={"title": "Isla del tesoro", "total_chapters": 99}, headers=HDR)
    assert r.status_code == 201
    book = client.get(f"/books/{r.json()['id']}", headers=HDR).json()
    assert book["status"] == "ready"
    assert book["total_chapters"] == 5  # gana la fuente, no el hint

    # se resumió el texto real de los 5 capítulos
    assert fake.calls["summarize"] >= 1
    assert sorted(n for block in fake.summarized for n in block) == [1, 2, 3, 4, 5]
    # la generación de spoilers recibió los resúmenes
    assert fake.spoiler_summaries and all(s is not None for s in fake.spoiler_summaries)
    assert fake.calls["index"] == 0  # no cayó en Claude

    # los resúmenes se persistieron en Chapter.summary
    with Session(db.engine) as s:
        rows = s.exec(select(Chapter).where(Chapter.book_id == book["id"])).all()
    assert len(rows) == 5 and all(c.summary.startswith("Resumen real") for c in rows)


def test_grounded_uses_smaller_block_size(make_client, monkeypatch):
    monkeypatch.setenv("GROUND_SPOILERS", "true")
    _patch_source(monkeypatch, n=5, grounded=True)
    client, fake = make_client(FakeClaude())
    get_settings.cache_clear()
    assert get_settings().chapters_per_block_grounded == 4

    r = client.post("/books", json={"title": "Isla"}, headers=HDR)
    assert client.get(f"/books/{r.json()['id']}", headers=HDR).json()["status"] == "ready"
    # 5 capítulos en bloques de 4 -> 2 llamadas de spoilers (no 1 de 10)
    assert fake.calls["sync"] >= 2


def test_not_grounded_skips_stage_b(make_client, monkeypatch):
    monkeypatch.setenv("GROUND_SPOILERS", "true")  # activado, pero la fuente no funda
    _patch_source(monkeypatch, n=6, grounded=False)
    client, fake = make_client(FakeClaude())

    r = client.post("/books", json={"title": "Un libro"}, headers=HDR)
    assert client.get(f"/books/{r.json()['id']}", headers=HDR).json()["status"] == "ready"
    assert fake.calls["summarize"] == 0  # etapa B omitida
    assert fake.spoiler_summaries and all(s is None for s in fake.spoiler_summaries)
