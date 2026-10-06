import sqlite3

from app import db
from app.services.sprite import validate_sprite
from tests.fakes import FakeClaude, good_sprite


def test_pregeneration_stores_scene_per_chapter(make_client):
    c, _ = make_client(FakeClaude(total=12))
    bid = c.post("/books", json={"title": "Veinte mil leguas"}).json()["id"]
    rows = c.get(f"/books/{bid}/spoilers").json()
    assert len(rows) == 12
    sc = rows[2]["escena"]
    assert sc["nudo"] == "Nudo del capítulo 3" and sc["desenlace"] == "Desenlace del capítulo 3"
    assert [e["nombre"] for e in sc["elementos"]] == ["Objeto 3", "Animal 3"]


def test_regenerate_picks_a_different_subject(make_client):
    c, fake = make_client(FakeClaude(total=5))
    bid = c.post("/books", json={"title": "Libro"}).json()["id"]
    w1 = c.put(f"/books/{bid}/progress", json={"current_chapter": 1}).json()
    w2 = c.post(f"/books/{bid}/sprite/regenerate").json()
    assert w1["sprite"]["subject"] == "Objeto 2" and w2["sprite"]["subject"] == "Animal 2"
    assert fake.sprite_prompts[-1]["used"] == ["Objeto 2"]
    hist = c.get(f"/books/{bid}/sprites").json()
    assert [s["subject"] for s in hist] == ["Animal 2", "Objeto 2"]


def test_sprite_without_scene_uses_plan_b(make_client):
    c, fake = make_client(FakeClaude(total=3))
    bid = c.post("/books", json={"title": "Corto"}).json()["id"]
    c.put(f"/books/{bid}/progress", json={"current_chapter": 3})  # libro terminado: no hay capítulo 4
    assert fake.sprite_prompts[-1]["scene"] is None


def test_edit_scene(make_client):
    c, fake = make_client(FakeClaude(total=3))
    bid = c.post("/books", json={"title": "Editable"}).json()["id"]
    body = {"nudo": "El Nautilus queda atrapado", "desenlace": "Escapan",
            "elementos": [{"tipo": "objeto", "nombre": "Nautilus", "detalle": "casco negro con remaches"},
                          {"tipo": "raro", "nombre": "Hielo"}]}
    r = c.put(f"/books/{bid}/scenes/2", json=body)
    assert r.status_code == 200
    assert r.json()["escena"]["elementos"][1] == {"tipo": "objeto", "nombre": "Hielo", "detalle": ""}
    assert c.get(f"/books/{bid}/spoilers").json()[1]["escena"]["nudo"] == "El Nautilus queda atrapado"
    c.put(f"/books/{bid}/progress", json={"current_chapter": 1})
    assert fake.sprite_prompts[-1]["scene"]["elementos"][0]["nombre"] == "Nautilus"
    assert c.put(f"/books/{bid}/scenes/9", json=body).status_code == 404
    assert c.put(f"/books/{bid}/scenes/1", json={**body, "elementos": []}).status_code == 422


def test_delete_book_removes_scenes(make_client):
    c, _ = make_client(FakeClaude(total=3))
    bid = c.post("/books", json={"title": "Borrar"}).json()["id"]
    assert c.delete(f"/books/{bid}").status_code == 204


def test_migration_adds_sprite_subject(tmp_path, monkeypatch):
    path = tmp_path / "old.db"
    con = sqlite3.connect(path)
    con.execute(
        "CREATE TABLE sprite (id INTEGER PRIMARY KEY, book_id INTEGER, chapter_number INTEGER, name TEXT,"
        " palette_json TEXT, pixels_json TEXT, frames_json TEXT, fallback BOOLEAN, created_at DATETIME)"
    )
    con.commit()
    con.close()
    db.init_engine(f"sqlite:///{path}")
    cols = [r[1] for r in sqlite3.connect(path).execute("PRAGMA table_info(sprite)")]
    assert "subject" in cols
    db.init_engine(f"sqlite:///{path}")  # idempotente


def test_sixteen_color_palette_is_valid():
    d = good_sprite()
    d["palette"] = [f"#{i:02x}{i:02x}{i:02x}" for i in range(16)]
    d["pixels"][20] = "." * 10 + "f" * 12 + "." * 10
    assert len(validate_sprite(d).palette) == 16
