"""Resolución del índice de un libro desde fuentes externas, antes de recurrir a Claude.

Orden (configurable en `settings.book_sources`): es.wikisource.org (API de MediaWiki) -> Claude.
Solo textos en español. El objetivo es no inventar capítulos ni fallar el conteo: Wikisource trae el
índice real (conteo + títulos reales siguiendo su jerarquía de subpáginas) y, para la etapa B, el texto
por capítulo con el que se fundamentan los pre-spoilers.

Todo es best-effort: cualquier fallo de red/parseo se registra y se pasa a la siguiente fuente; si
ninguna encuentra el libro, se usa `claude.index(...)` tal cual (comportamiento anterior).

Diseñado para ser monkeypatcheable en los tests: `resolve_index`/`chapter_texts` y `wikisource_lookup`
se llaman a través del módulo, y todo el acceso HTTP pasa por el helper `_wiki` que los tests sustituyen.
"""
import logging
import re
import unicodedata
from dataclasses import dataclass, field
from difflib import SequenceMatcher

import httpx

from app.config import get_settings
from app.services.claude_client import ClaudeClient
from app.services.themes import normalize_genre

log = logging.getLogger("bookwidget.sources")

_UA = "book-widget/0.1 (lector personal; https://localhost)"
WIKISOURCE_API = "https://es.wikisource.org/w/api.php"
_MIN_MATCH = 0.6  # similitud mínima de título antes de aceptar un candidato


# ---------------------------------------------------------------- estructuras
@dataclass
class SourceRef:
    """Lo necesario para recuperar el texto por capítulo en la etapa B."""
    source: str  # "wikisource"
    payload: dict = field(default_factory=dict)


@dataclass
class IndexResult:
    known: bool
    genre: str
    chapters: list[dict]  # [{"n": int, "title": str}]
    grounded: bool = False
    source_ref: SourceRef | None = None


@dataclass
class _Raw:
    source: str
    candidate: str              # descripción para que Claude confirme la coincidencia
    chapters: list[dict]        # [{"n", "title"}] best-effort
    genre: str
    toc_text: str               # índice crudo para structure_toc si hiciera falta
    ref: SourceRef
    structurable: bool = False  # True = pedir a Claude nombres cuando los títulos son genéricos


# ---------------------------------------------------------------- utilidades
def _norm(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()


def _similar(a: str, b: str) -> float:
    return SequenceMatcher(None, _norm(a), _norm(b)).ratio()


def _is_generic(title: str) -> bool:
    t = (title or "").strip()
    return not t or bool(re.match(r"^(cap(i|í)tulo|cap\.?|chapter|parte|tomo|libro)\s*[\dIVXLCMivxlcm]*\.?$", t, re.I))


def _names_are_generic(chapters: list[dict]) -> bool:
    if not chapters:
        return True
    generic = sum(1 for c in chapters if _is_generic(str(c.get("title") or "")))
    return generic > len(chapters) // 2


def _renumber(chapters: list[dict]) -> list[dict]:
    out = []
    for i, c in enumerate(sorted(chapters, key=lambda x: x.get("n", 0)), start=1):
        out.append({"n": i, "title": str(c.get("title") or "").strip()})
    return out


def _client() -> httpx.Client:
    return httpx.Client(
        timeout=get_settings().book_source_timeout,
        headers={"User-Agent": _UA},
        follow_redirects=True,
    )


# ---------------------------------------------------------- números (orden)
_ROMAN = {"i": 1, "v": 5, "x": 10, "l": 50, "c": 100, "d": 500, "m": 1000}
_CARDINAL = {
    "uno": 1, "una": 1, "dos": 2, "tres": 3, "cuatro": 4, "cinco": 5, "seis": 6, "siete": 7,
    "ocho": 8, "nueve": 9, "diez": 10, "once": 11, "doce": 12, "trece": 13, "catorce": 14,
    "quince": 15, "dieciseis": 16, "diecisiete": 17, "dieciocho": 18, "diecinueve": 19, "veinte": 20,
    "veintiuno": 21, "veintidos": 22, "veintitres": 23, "veinticuatro": 24, "veinticinco": 25,
    "veintiseis": 26, "veintisiete": 27, "veintiocho": 28, "veintinueve": 29, "treinta": 30,
}
_ORDINAL = {
    "primero": 1, "primera": 1, "primer": 1, "segundo": 2, "segunda": 2, "tercero": 3, "tercera": 3,
    "cuarto": 4, "cuarta": 4, "quinto": 5, "quinta": 5, "sexto": 6, "sexta": 6, "septimo": 7,
    "septima": 7, "octavo": 8, "octava": 8, "noveno": 9, "novena": 9, "decimo": 10, "decima": 10,
}
_NUM_WORD = {**_CARDINAL, **_ORDINAL}
_CHAP_RE = re.compile(r"^\s*(?:cap(?:í|i)tulo|cap\.?|chapter)\s+([^\s.:]+)", re.I)
_CHAPTERISH = re.compile(r"^\s*(cap(?:í|i)tulo|cap\.?|chapter)\b", re.I)


def _roman(s: str) -> int | None:
    s = _norm(s).replace(" ", "")
    if not s or any(ch not in _ROMAN for ch in s):
        return None
    total, prev = 0, 0
    for ch in reversed(s):
        v = _ROMAN[ch]
        total += -v if v < prev else v
        prev = max(prev, v)
    return total or None


def _parse_num(token: str) -> int | None:
    t = _norm(token)
    if not t:
        return None
    if t.isdigit():
        return int(t)
    return _NUM_WORD.get(t) or _roman(t)


def _chapter_num(segment: str) -> int | None:
    m = _CHAP_RE.match(segment)
    return _parse_num(m.group(1)) if m else None


def _part_num(segment: str) -> int | None:
    for tok in _norm(segment).split():
        if tok in ("parte", "tomo", "libro"):
            continue
        n = _parse_num(tok)
        if n is not None:
            return n
    return None


def _is_chapter_page(segment: str) -> bool:
    return bool(_CHAPTERISH.match(segment))


# ---------------------------------------------------------------- Wikisource
def _wiki(**params) -> dict:
    params.setdefault("format", "json")
    with _client() as c:
        r = c.get(WIKISOURCE_API, params=params)
        r.raise_for_status()
        return r.json()


def _ws_search(title: str) -> list[str]:
    r = _wiki(action="query", list="search", srsearch=title, srnamespace=0, srlimit=6)
    return [h["title"] for h in r.get("query", {}).get("search", [])]


def _ws_allpages(prefix: str) -> list[str]:
    out: list[str] = []
    cont: dict = {}
    while True:
        r = _wiki(action="query", list="allpages", apprefix=prefix, apnamespace=0, aplimit=500, **cont)
        out += [p["title"] for p in r.get("query", {}).get("allpages", [])]
        cont = r.get("continue") or {}
        if not cont or len(out) > 2000:
            break
    return out


def _ws_links(page: str) -> list[str]:
    r = _wiki(action="parse", page=page, prop="links")
    return [l["*"] for l in r.get("parse", {}).get("links", []) if l.get("ns") == 0]


def _ws_gather(root: str) -> list[tuple[int, str, str]]:
    """[(orden, título, pagetitle)] de los capítulos subpágina de `root`. [] si no hay.
    Soporta plano (root/Capítulo X) y anidado (root/Parte/Capítulo X)."""
    subs = _ws_allpages(root + "/")
    flat: list[tuple[int | None, str, str]] = []
    nested: dict[tuple[int, str], list[tuple[int, str, str]]] = {}
    for p in subs:
        segs = [s for s in p[len(root) + 1:].split("/") if s]
        if len(segs) == 1 and _is_chapter_page(segs[0]):
            flat.append((_chapter_num(segs[0]), segs[0], p))
        elif len(segs) == 2 and _is_chapter_page(segs[1]):
            pnum, cnum = _part_num(segs[0]), _chapter_num(segs[1])
            if pnum is not None and cnum is not None:
                nested.setdefault((pnum, segs[0]), []).append((cnum, segs[1], p))
    if nested:
        out: list[tuple[int, str, str]] = []
        for pnum, plabel in sorted(nested):
            for _cnum, chlabel, page in sorted(nested[(pnum, plabel)]):
                out.append((len(out) + 1, f"{plabel} · {chlabel}", page))
        return out
    if flat:
        # los numerados primero (en orden), luego los que no se pudieron numerar (p. ej. erratas).
        flat.sort(key=lambda t: (t[0] is None, t[0] if t[0] is not None else 0, t[1]))
        return [(i, label, page) for i, (_n, label, page) in enumerate(flat, start=1)]
    return []


def _ws_chapters(page: str) -> tuple[str, list[tuple[int, str, str]]]:
    """Devuelve (raíz_de_la_obra, capítulos). Si la página es de desambiguación de traducciones,
    desciende por sus enlaces a la edición con más capítulos."""
    chaps = _ws_gather(page)
    if len(chaps) >= 2:
        return page, chaps
    best_root, best = page, chaps
    for link in _ws_links(page):
        if link == page or not link.startswith(page):
            continue
        g = _ws_gather(link)
        if len(g) > len(best):
            best_root, best = link, g
    return best_root, best


def wikisource_lookup(title: str, author: str) -> _Raw | None:
    try:
        hits = _ws_search(title)
        if not hits:
            return None
        best = max(hits, key=lambda h: _similar(title, h))
        if _similar(title, best) < _MIN_MATCH:
            return None
        root, chaps = _ws_chapters(best)
    except (httpx.HTTPError, ValueError, KeyError) as e:
        log.warning("Wikisource: consulta falló: %s", e)
        return None
    if len(chaps) < 2:
        return None
    chapters = [{"n": n, "title": t} for n, t, _ in chaps]
    candidate = f"Título de la página: {root}\nFuente: Wikisource (es)\nAutor buscado: {author or '(no indicado)'}"
    ref = SourceRef("wikisource", {"chapters": [(n, pg) for n, _, pg in chaps]})
    # Los títulos salen del propio árbol de páginas: son canónicos, no se piden nombres a Claude.
    return _Raw("wikisource", candidate, chapters, "otro", "\n".join(t for _, t, _ in chaps), ref,
                structurable=False)


_WIKI_TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
_WIKI_LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]|]+)\]\]")
_WIKI_REF = re.compile(r"<ref[^>]*>.*?</ref>|<ref[^>]*/>", re.I | re.S)
_WIKI_TAG = re.compile(r"<[^>]+>")


def _strip_wikitext(s: str) -> str:
    s = _WIKI_REF.sub("", s)
    for _ in range(3):  # plantillas anidadas
        s = _WIKI_TEMPLATE.sub("", s)
    s = _WIKI_LINK.sub(r"\1", s)
    s = _WIKI_TAG.sub("", s)
    s = re.sub(r"'''?|==+", "", s)
    return re.sub(r"\n{3,}", "\n\n", s).strip()


# ---------------------------------------------------------------- orquestación
def resolve_index(title: str, author: str, synopsis: str, hint: int | None, claude: ClaudeClient) -> IndexResult:
    settings = get_settings()
    order = [s.strip() for s in settings.book_sources.split(",") if s.strip()]
    for name in order:
        # Se resuelve por nombre en cada llamada (no un dict a nivel de módulo) para que los tests
        # puedan monkeypatchear wikisource_lookup.
        lookup = {"wikisource": wikisource_lookup}.get(name)
        if lookup is None:
            continue
        try:
            raw = lookup(title, author)
        except Exception as e:  # noqa: BLE001 - ninguna fuente debe romper el alta
            log.warning("fuente %s falló: %s", name, e)
            continue
        if raw is None or not raw.chapters:
            continue
        try:
            if not claude.confirm_book(title, author, raw.candidate):
                log.info("%s: candidato descartado por Claude", name)
                continue
        except Exception as e:  # noqa: BLE001
            log.warning("confirmación de %s falló: %s", name, e)
            continue

        chapters = _renumber(raw.chapters)
        if raw.structurable and _names_are_generic(chapters):
            try:
                structured = _renumber(claude.structure_toc(title, author, raw.toc_text, len(chapters)))
                if len(structured) == len(chapters):  # el conteo de la fuente manda
                    chapters = structured
                else:
                    log.warning("structure_toc de %s devolvió %s de %s capítulos; se mantienen los originales",
                                name, len(structured), len(chapters))
            except Exception as e:  # noqa: BLE001
                log.warning("structure_toc de %s falló: %s", name, e)
        genre = normalize_genre(raw.genre)
        log.info("índice resuelto por %s: %s capítulos", name, len(chapters))
        return IndexResult(True, genre, chapters, grounded=True, source_ref=raw.ref)

    # Fallback: Claude de memoria (comportamiento anterior).
    res = claude.index(title, author, synopsis, hint)
    return IndexResult(
        known=bool(res.get("known")),
        genre=normalize_genre(res.get("genre")),
        chapters=res.get("chapters") or [],
        grounded=False,
        source_ref=None,
    )


def chapter_texts(source_ref: SourceRef | None) -> dict[int, str]:
    """Texto real por número de capítulo para la etapa B. {} si no se puede recuperar."""
    if source_ref is None:
        return {}
    cap = get_settings().chapter_text_max_chars
    try:
        if source_ref.source == "wikisource":
            return _wikisource_texts(source_ref.payload, cap)
    except Exception as e:  # noqa: BLE001
        log.warning("recuperación de texto (%s) falló: %s", source_ref.source, e)
    return {}


def _wikisource_texts(payload: dict, cap: int) -> dict[int, str]:
    out: dict[int, str] = {}
    for n, page in payload.get("chapters") or []:
        try:
            wt = _wiki(action="parse", page=page, prop="wikitext").get("parse", {}).get("wikitext", {}).get("*", "")
        except (httpx.HTTPError, ValueError, KeyError) as e:  # noqa: PERF203
            log.warning("Wikisource: texto de %r falló: %s", page, e)
            continue
        text = _strip_wikitext(wt)
        if text:
            out[n] = text[:cap]
    return out
