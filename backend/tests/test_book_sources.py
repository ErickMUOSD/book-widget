"""Unit de book_sources: parseo de números, jerarquía de Wikisource, Internet Archive y la
orquestación de resolve_index (todo sin red: se monkeypatchean los helpers HTTP / *_lookup)."""
from app.config import get_settings
from app.services import book_sources as bs
from app.services.book_sources import IndexResult, SourceRef, _Raw
from tests.fakes import FakeClaude


# -------------------------------------------------------------- helpers puros
def test_is_generic():
    assert bs._is_generic("Capítulo 1") and bs._is_generic("CAPITULO XIV") and bs._is_generic("")
    assert not bs._is_generic("La casa junto al río")


def test_names_are_generic_majority():
    assert bs._names_are_generic([{"n": i, "title": f"Capítulo {i}"} for i in range(1, 5)])
    assert not bs._names_are_generic([{"n": i, "title": f"Título {i}"} for i in range(1, 5)])


def test_number_parsing():
    assert bs._roman("IX") == 9 and bs._roman("xiv") == 14 and bs._roman("mcm") == 1900
    assert bs._roman("zzz") is None
    assert bs._parse_num("7") == 7 and bs._parse_num("Uno") == 1 and bs._parse_num("VIII") == 8
    assert bs._chapter_num("Capítulo IX") == 9 and bs._chapter_num("Capítulo Uno") == 1
    assert bs._chapter_num("Epílogo") is None
    assert bs._part_num("Primera Parte") == 1 and bs._part_num("Sexta Parte") == 6
    assert bs._part_num("Parte III") == 3


def test_strip_wikitext():
    out = bs._strip_wikitext("{{Encabezado}}El [[héroe|protagonista]] '''corrió''' <ref>n</ref> al bosque.")
    assert "protagonista" in out and "corrió" in out
    assert "{{" not in out and "[[" not in out and "<ref>" not in out


# -------------------------------------------------------------- Wikisource
def test_ws_gather_flat_orders_by_number(monkeypatch):
    root = "El jugador"
    pages = [f"{root}/Capítulo {r}" for r in ("I", "II", "III", "IX", "X")]
    monkeypatch.setattr(bs, "_ws_allpages", lambda prefix: pages)
    chaps = bs._ws_gather(root)
    assert [t for _, t, _ in chaps] == ["Capítulo I", "Capítulo II", "Capítulo III", "Capítulo IX", "Capítulo X"]
    assert [n for n, _, _ in chaps] == [1, 2, 3, 4, 5]  # renumerado consecutivo


def test_ws_gather_keeps_unparseable_leaf_last(monkeypatch):
    root = "Canterville"
    pages = [f"{root}/Capítulo Uno", f"{root}/Capítulo Seix", f"{root}/Capítulo Dos"]  # "Seix" = errata real
    monkeypatch.setattr(bs, "_ws_allpages", lambda prefix: pages)
    chaps = bs._ws_gather(root)
    assert len(chaps) == 3  # no se pierde el capítulo con errata
    assert chaps[-1][1] == "Capítulo Seix"  # el no numerable queda al final


def test_ws_gather_nested_parts(monkeypatch):
    root = "Crimen y castigo (tr. anónima)"
    pages = [
        f"{root}/Primera Parte", f"{root}/Primera Parte/Capítulo I", f"{root}/Primera Parte/Capítulo II",
        f"{root}/Segunda Parte/Capítulo I", f"{root}/Epílogo",
    ]
    monkeypatch.setattr(bs, "_ws_allpages", lambda prefix: pages)
    chaps = bs._ws_gather(root)
    assert [t for _, t, _ in chaps] == [
        "Primera Parte · Capítulo I", "Primera Parte · Capítulo II", "Segunda Parte · Capítulo I"]
    assert [n for n, _, _ in chaps] == [1, 2, 3]


def test_ws_chapters_descends_into_translation(monkeypatch):
    page = "Crimen y castigo"
    tr = "Crimen y castigo (tr. anónima)"
    allpages = {
        page + "/": [],  # la página raíz es desambiguación: sin subpáginas de capítulo
        tr + "/": [f"{tr}/Primera Parte/Capítulo I", f"{tr}/Primera Parte/Capítulo II"],
    }
    monkeypatch.setattr(bs, "_ws_allpages", lambda prefix: allpages.get(prefix, []))
    monkeypatch.setattr(bs, "_ws_links", lambda p: [tr, "Página sin relación"])
    root, chaps = bs._ws_chapters(page)
    assert root == tr and len(chaps) == 2


# -------------------------------------------------------------- orquestación
def _use(monkeypatch, source: str):
    monkeypatch.setenv("BOOK_SOURCES", source)
    get_settings.cache_clear()


def test_resolve_index_wikisource_keeps_canonical_titles(monkeypatch):
    raw = _Raw("wikisource", "cand",
               [{"n": 1, "title": "Capítulo I"}, {"n": 2, "title": "Capítulo II"}],
               "otro", "toc", SourceRef("wikisource", {}), structurable=False)
    monkeypatch.setattr(bs, "wikisource_lookup", lambda t, a: raw)
    _use(monkeypatch, "wikisource")
    fake = FakeClaude()
    res = bs.resolve_index("El jugador", "Dostoyevski", "", None, fake)
    assert isinstance(res, IndexResult) and res.grounded
    assert fake.calls["confirm"] == 1 and fake.calls["structure"] == 0  # NO se inventan nombres
    assert [c["title"] for c in res.chapters] == ["Capítulo I", "Capítulo II"]
    assert fake.calls["index"] == 0


def test_resolve_index_cleans_generic_names_when_structurable(monkeypatch):
    # Caso sintético: una fuente marcada structurable=True con títulos genéricos -> structure_toc.
    raw = _Raw("wikisource", "cand",
               [{"n": i, "title": f"Capítulo {i}"} for i in range(1, 4)],
               "aventura", "toc", SourceRef("wikisource", {}), structurable=True)
    monkeypatch.setattr(bs, "wikisource_lookup", lambda t, a: raw)
    _use(monkeypatch, "wikisource")
    fake = FakeClaude()
    res = bs.resolve_index("Libro", "Autor", "", None, fake)
    assert res.grounded and fake.calls["structure"] == 1  # genéricos -> structure_toc
    assert all("Nombre real" in c["title"] for c in res.chapters)


def test_resolve_index_falls_back_when_not_confirmed(monkeypatch):
    raw = _Raw("wikisource", "cand", [{"n": 1, "title": "Uno"}, {"n": 2, "title": "Dos"}],
               "otro", "toc", SourceRef("wikisource", {}))
    monkeypatch.setattr(bs, "wikisource_lookup", lambda t, a: raw)
    _use(monkeypatch, "wikisource")
    fake = FakeClaude(total=7, confirm_result=False)  # Claude rechaza el candidato
    res = bs.resolve_index("Libro", "Autor", "", None, fake)
    assert not res.grounded and fake.calls["confirm"] == 1 and fake.calls["index"] == 1
    assert len(res.chapters) == 7
