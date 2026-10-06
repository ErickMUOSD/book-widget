import time

from app.config import get_settings
from app.services.pregenerate import poll_batches
from tests.fakes import FakeClaude, good_sprite


def spoiler_count(c, book_id):
    return sum(len(v) - 1 for v in c.get(f"/books/{book_id}/spoilers").json())


def test_create_book_sync_pregenerates_all_levels(make_client):
    c, fake = make_client(FakeClaude(total=25, genre="fantasía"))
    r = c.post("/books", json={"title": "El Hobbit", "author": "Tolkien"})
    assert r.status_code == 201
    b = c.get(f"/books/{r.json()['id']}").json()
    assert b["status"] == "ready"
    assert b["genre"] == "fantasia" and b["theme"]["id"] == "fantasia"
    assert b["total_chapters"] == 25 and len(b["chapters"]) == 25
    assert b["spoilers_ready"] == b["spoilers_expected"] == 75
    assert fake.calls["index"] == 1 and fake.calls["sync"] == 3  # bloques de 10: 10+10+5
    assert b["is_active"] is True


def test_global_cache_reuses_existing_book(make_client):
    c, fake = make_client()
    a = c.post("/books", json={"title": "Dracula", "author": "Bram Stoker"}).json()
    r = c.post("/books", json={"title": "  DRÁCULA ", "author": "bram  stoker"})
    assert r.status_code == 200 and r.json()["id"] == a["id"]
    assert fake.calls["index"] == 1


def test_batch_mode_then_poll(make_client):
    c, fake = make_client(FakeClaude(total=12), use_batch=True)
    bid = c.post("/books", json={"title": "Libro batch"}).json()["id"]
    b = c.get(f"/books/{bid}").json()
    assert b["status"] == "pending" and fake.calls["submit"] == 1 and fake.calls["sync"] == 0
    # el batch aún corre: el poller no hace nada
    poll_batches(fake)
    assert c.get(f"/books/{bid}").json()["status"] == "pending"
    fake.batch_state = "ended"
    poll_batches(fake)
    b = c.get(f"/books/{bid}").json()
    assert b["status"] == "ready" and b["spoilers_ready"] == 36


def test_long_caption_retried_only_for_missing(make_client):
    c, fake = make_client(FakeClaude(total=12, bad_words_for={3}))
    bid = c.post("/books", json={"title": "Largo"}).json()["id"]
    b = c.get(f"/books/{bid}").json()
    assert b["status"] == "ready"
    sp = {s["chapter"]: s for s in c.get(f"/books/{bid}/spoilers").json()}
    assert all(len(s["niebla"].split()) <= 50 for s in sp.values())
    assert fake.calls["sync"] == 2 + 1  # 2 bloques + 1 reintento solo del capítulo 3


def test_unknown_book_asks_for_info_then_retries(make_client):
    c, fake = make_client(FakeClaude(known=False))
    b = c.post("/books", json={"title": "Libro oscuro"}).json()
    got = c.get(f"/books/{b['id']}").json()
    assert got["status"] == "unknown" and "capítulos" in got["status_detail"]
    # progreso bloqueado hasta que el libro esté listo
    assert c.put(f"/books/{b['id']}/progress", json={"current_chapter": 1}).status_code == 409
    r = c.post("/books", json={"title": "Libro oscuro", "total_chapters": 4, "synopsis": "Un faro."})
    assert r.status_code == 200
    got = c.get(f"/books/{b['id']}").json()
    assert got["status"] == "ready" and got["total_chapters"] == 4 and got["spoilers_ready"] == 12


def test_progress_sprite_sealed_and_next_caption(make_client):
    c, fake = make_client()
    bid = c.post("/books", json={"title": "Libro"}).json()["id"]
    w = c.put(f"/books/{bid}/progress", json={"current_chapter": 3, "spoiler_level": "peligro"}).json()
    assert w["caption"] == "Peligro del capítulo 4"  # pre-spoiler del capítulo SIGUIENTE
    assert w["sealed"] is True and w["next_chapter"] == 4 and w["finished"] is False
    assert w["sprite"]["name"] == "Heroe 3" and w["sprite"]["frame_count"] == 3
    # el sprite recibe la ficha visual del capítulo SIGUIENTE y la paleta del tema
    assert fake.sprite_prompts[-1]["scene"]["nudo"] == "Nudo del capítulo 4"
    assert w["sprite"]["subject"] == "Objeto 4"
    assert len(fake.sprite_prompts[-1]["palette"]) == 8
    # mismo capítulo: no se genera otro sprite
    c.put(f"/books/{bid}/progress", json={"current_chapter": 3})
    assert fake.calls["sprite"] == 1
    # cambiar de capítulo: sprite nuevo y vuelve a sellar
    c.patch(f"/books/{bid}/state", json={"sealed": False})
    w = c.put(f"/books/{bid}/progress", json={"current_chapter": 4}).json()
    assert fake.calls["sprite"] == 2 and w["sealed"] is True and w["sprite"]["name"] == "Heroe 4"


def test_patch_state_costs_no_claude_calls(make_client):
    c, fake = make_client()
    bid = c.post("/books", json={"title": "Libro"}).json()["id"]
    c.put(f"/books/{bid}/progress", json={"current_chapter": 1})
    before = dict(fake.calls)
    w = c.patch(f"/books/{bid}/state", json={"sealed": False, "spoiler_level": "niebla"}).json()
    assert w["sealed"] is False and w["caption"] == "Niebla del capítulo 2"
    assert c.patch(f"/books/{bid}/state", json={"spoiler_level": "x"}).status_code == 422
    assert fake.calls == before


def test_advance_and_end_of_book(make_client):
    c, fake = make_client(FakeClaude(total=3))
    bid = c.post("/books", json={"title": "Corto"}).json()["id"]
    assert c.post(f"/books/{bid}/advance").json()["current_chapter"] == 1
    c.post(f"/books/{bid}/advance")
    w = c.post(f"/books/{bid}/advance").json()
    assert w["current_chapter"] == 3 and w["finished"] is True and w["caption"] is None and w["next_chapter"] is None
    again = c.post(f"/books/{bid}/advance").json()
    assert again["current_chapter"] == 3
    assert c.put(f"/books/{bid}/progress", json={"current_chapter": 9}).status_code == 422


def test_invalid_sprite_is_retried_with_error_then_fallback(make_client):
    bad = good_sprite()
    bad["pixels"] = bad["pixels"][:5]
    c, fake = make_client(FakeClaude(sprite_outputs=[bad, good_sprite("Bien")]))
    bid = c.post("/books", json={"title": "A"}).json()["id"]
    w = c.put(f"/books/{bid}/progress", json={"current_chapter": 1}).json()
    assert w["sprite"]["name"] == "Bien" and not w["sprite"]["fallback"]
    assert "32 filas" in fake.sprite_prompts[1]["error"]  # el error se reenvía al modelo

    c2, fake2 = make_client(FakeClaude(sprite_outputs=[bad, bad]))
    bid = c2.post("/books", json={"title": "B"}).json()["id"]
    w = c2.put(f"/books/{bid}/progress", json={"current_chapter": 1}).json()
    assert w["sprite"]["fallback"] is True and w["sprite"]["name"] == "Librito"

    c3, _ = make_client(FakeClaude(sprite_outputs=[RuntimeError("sin cuota")]))
    bid = c3.post("/books", json={"title": "C"}).json()["id"]
    assert c3.put(f"/books/{bid}/progress", json={"current_chapter": 1}).json()["sprite"]["fallback"] is True


def test_images_and_share_card(make_client):
    c, _ = make_client()
    bid = c.post("/books", json={"title": "Libro"}).json()["id"]
    w = c.put(f"/books/{bid}/progress", json={"current_chapter": 2}).json()
    for url in w["sprite"]["frame_urls"]:
        r = c.get(url)
        assert r.status_code == 200 and r.content.startswith(b"\x89PNG")
    assert c.get(f"/sprites/{w['sprite']['id']}/9.png").status_code == 404
    for sealed in ("true", "false"):
        r = c.get(f"/books/{bid}/share-card.png?sealed={sealed}")
        assert r.status_code == 200 and r.headers["content-type"] == "image/png"


def test_edit_spoiler_validates_words(make_client):
    c, _ = make_client()
    bid = c.post("/books", json={"title": "Libro"}).json()["id"]
    ok = c.put(f"/books/{bid}/spoilers/2/pista", json={"caption": "Algo se mueve en el sótano."})
    assert ok.status_code == 200
    assert c.put(f"/books/{bid}/spoilers/2/pista", json={"caption": "x " * 51}).status_code == 422
    assert c.put(f"/books/{bid}/spoilers/99/pista", json={"caption": "x"}).status_code == 404
    c.put(f"/books/{bid}/progress", json={"current_chapter": 1, "spoiler_level": "pista"})
    assert c.get(f"/books/{bid}/widget").json()["caption"] == "Algo se mueve en el sótano."


def test_active_book_and_delete(make_client):
    c, _ = make_client()
    a = c.post("/books", json={"title": "A"}).json()["id"]
    b = c.post("/books", json={"title": "B"}).json()["id"]
    assert c.get("/widget").json()["book_id"] == a  # el primero queda activo
    c.post(f"/books/{b}/activate")
    assert c.get("/widget").json()["book_id"] == b
    assert [x["is_active"] for x in c.get("/books").json()] == [True, False]  # orden: id desc
    assert c.delete(f"/books/{b}").status_code == 204
    assert c.get(f"/books/{b}").status_code == 404


def test_auth_and_devices(make_client):
    c, _ = make_client()
    anon = type(c)(c.app)
    assert anon.get("/books").status_code == 401
    assert anon.get("/health").status_code == 200
    assert anon.get("/books", headers={"X-Device-Token": "nope"}).status_code == 401
    dev = c.post("/devices", json={"name": "Pixel 8"})
    token = dev.json()["token"]
    assert anon.get("/books", headers={"X-Device-Token": token}).status_code == 200
    # un dispositivo no puede gestionar dispositivos
    assert anon.get("/devices", headers={"X-Device-Token": token}).status_code == 403
    listing = c.get("/devices").json()
    assert listing == [{"id": dev.json()["id"], "name": "Pixel 8", "created_at": listing[0]["created_at"]}]
    assert c.delete(f"/devices/{dev.json()['id']}").status_code == 204
    assert anon.get("/books", headers={"X-Device-Token": token}).status_code == 401
