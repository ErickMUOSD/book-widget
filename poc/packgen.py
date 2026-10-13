#!/usr/bin/env python3
"""Prueba de concepto: libro libre -> capítulos reales -> pre-spoilers leyendo el texto -> pack JSON.

Solo biblioteca estándar. Pasos:
  python3 poc/packgen.py search "dracula" [--lang es]   # Gutendex (Project Gutenberg)
  python3 poc/packgen.py chapters 345                   # EPUB -> índice real, SIN IA
  GEMINI_API_KEY=... python3 poc/packgen.py pack 345 [--max 3]   # + Gemini -> poc/packs/<id>.json

La clave sale de https://aistudio.google.com/apikey (cuota gratis de quien la crea).
"""
from __future__ import annotations

import argparse
import io
import json
import os
import posixpath
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path

UA = {"User-Agent": "book-widget-poc/0.1 (+https://github.com)"}
GUTENDEX = "https://gutendex.com/books/"
# Se prueban en orden; ante 403/429/503 intermitentes (frecuentes en el nivel gratuito) se pasa al siguiente.
GEMINI_MODELS = os.environ.get("GEMINI_MODELS", "gemini-flash-latest,gemini-3.8-flash,gemini-flash-lite-latest").split(",")
GEMINI_MODEL = GEMINI_MODELS[0]
MAX_WORDS = 50
MAX_CHAPTER_CHARS = 120_000  # ~30k tokens: de sobra para un capítulo, cabe en Flash
CACHE = Path(__file__).resolve().parent / ".cache"
PACKS = Path(__file__).resolve().parent / "packs"


# ---------- red ----------
def http_get(url: str, cache_name: str | None = None) -> bytes:
    if cache_name:
        p = CACHE / cache_name
        if p.exists():
            return p.read_bytes()
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    if cache_name:
        CACHE.mkdir(exist_ok=True)
        (CACHE / cache_name).write_bytes(data)
    return data


def http_json(url: str) -> dict:
    return json.loads(http_get(url))


# ---------- 1. búsqueda (Gutendex) ----------
def search(query: str, lang: str | None) -> list[dict]:
    params = {"search": query}
    if lang:
        params["languages"] = lang
    data = http_json(GUTENDEX + "?" + urllib.parse.urlencode(params))
    return [b for b in data["results"] if "application/epub+zip" in b["formats"]]


def book_meta(gid: int) -> dict:
    return json.loads(http_get(f"{GUTENDEX}{gid}", cache_name=f"pg{gid}.json"))


def wikidata_qid(gid: int) -> str | None:
    """Clave universal del libro: el ítem de Wikidata con «Project Gutenberg ebook ID» (P2034) = gid."""
    url = "https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode({
        "action": "query", "list": "search", "srsearch": f"haswbstatement:P2034={gid}", "format": "json",
    })
    try:
        hits = http_json(url)["query"]["search"]
        if not hits:
            return None
        qid = hits[0]["title"]
        # suele ser la edición de Gutenberg: subimos a la obra («edition or translation of», P629)
        ent = http_json("https://www.wikidata.org/wiki/Special:EntityData/" + qid + ".json")["entities"][qid]
        work = ent.get("claims", {}).get("P629") or []
        return work[0]["mainsnak"]["datavalue"]["value"]["id"] if work else qid
    except Exception:  # noqa: BLE001 - es un extra
        return None


# ---------- 2. EPUB -> capítulos (sin IA) ----------
BLOCK = {"p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote", "section", "pre", "hr"}


class _Text(HTMLParser):
    """Texto plano de un XHTML + posición (en caracteres) de cada id/name, para cortar por anclas."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.size = 0
        self.anchors: dict[str, int] = {}
        self.skip = 0

    def _add(self, s: str):
        self.parts.append(s)
        self.size += len(s)

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "head"):
            self.skip += 1
        for k, v in attrs:
            if k in ("id", "name") and v and v not in self.anchors:
                self.anchors[v] = self.size
        if tag in BLOCK:
            self._add("\n")

    def handle_endtag(self, tag):
        if tag in ("script", "style", "head"):
            self.skip = max(0, self.skip - 1)
        if tag in BLOCK:
            self._add("\n")

    def handle_data(self, data):
        if not self.skip:
            self._add(data)


def _ns(tag: str) -> str:
    return tag.split("}", 1)[-1]


def _join(base: str, href: str) -> tuple[str, str]:
    path, _, frag = urllib.parse.unquote(href).partition("#")
    return posixpath.normpath(posixpath.join(base, path)) if path else "", frag


def _toc_ncx(z: zipfile.ZipFile, path: str) -> list[dict]:
    root = ET.fromstring(z.read(path))
    base = posixpath.dirname(path)
    out: list[dict] = []

    def walk(node, depth, parent=""):
        for np in [c for c in node if _ns(c.tag) == "navPoint"]:
            label = "".join(t for e in np.iter() if _ns(e.tag) == "text" for t in [e.text or ""]).strip()
            content = next(e for e in np.iter() if _ns(e.tag) == "content")
            kids = [c for c in np if _ns(c.tag) == "navPoint"]
            f, frag = _join(base, content.get("src", ""))
            title = " ".join(label.split())
            out.append({"title": title, "file": f, "frag": frag, "depth": depth, "leaf": not kids, "parent": parent})
            walk(np, depth + 1, title)

    walk(next(e for e in root.iter() if _ns(e.tag) == "navMap"), 0)
    return out


def _toc_nav(z: zipfile.ZipFile, path: str) -> list[dict]:
    root = ET.fromstring(z.read(path))
    base = posixpath.dirname(path)
    nav = next(
        (e for e in root.iter() if _ns(e.tag) == "nav" and "toc" in " ".join(e.attrib.values())),
        None,
    )
    out: list[dict] = []

    def walk(ol, depth, parent=""):
        for li in [c for c in ol if _ns(c.tag) == "li"]:
            a = next((c for c in li if _ns(c.tag) == "a"), None)
            sub = next((c for c in li if _ns(c.tag) == "ol"), None)
            title = " ".join("".join(a.itertext()).split()) if a is not None else parent
            if a is not None:
                f, frag = _join(base, a.get("href", ""))
                out.append({"title": title, "file": f, "frag": frag, "depth": depth, "leaf": sub is None,
                            "parent": parent})
            if sub is not None:
                walk(sub, depth + 1, title)

    if nav is not None:
        ol = next((c for c in nav if _ns(c.tag) == "ol"), None)
        if ol is not None:
            walk(ol, 0)
    return out


SKIP_TITLE = re.compile(
    r"gutenberg|licen|license|índice|indice|contents|contenido|portada|cover|title ?page|colof|colophon"
    r"|nota del transcriptor|transcriber|imprimatur|copyright|dedicatoria|footnotes|notas$",
    re.I,
)
MIN_CHAPTER_CHARS = 1500
# Si el índice usa esta forma de capítulo, lo que va antes (tasa, prólogo, «al lector»…) son preliminares.
CHAPTER_TITLE = re.compile(r"^(cap[ií]tulo|chapter|canto|libro|book|parte|part)\b|^[IVXLC]+\b\.?|^\d+\b", re.I)


def epub_chapters(epub: bytes) -> list[dict]:
    z = zipfile.ZipFile(io.BytesIO(epub))
    opf_path = ET.fromstring(z.read("META-INF/container.xml")).find(".//{*}rootfile").get("full-path")
    opf = ET.fromstring(z.read(opf_path))
    base = posixpath.dirname(opf_path)
    manifest = {i.get("id"): i for i in opf.iter() if _ns(i.tag) == "item"}
    spine = [posixpath.normpath(posixpath.join(base, urllib.parse.unquote(manifest[r.get("idref")].get("href"))))
             for r in opf.iter() if _ns(r.tag) == "itemref" and r.get("idref") in manifest]

    # índice: nav (EPUB3) o NCX (EPUB2)
    nav = next((i for i in manifest.values() if "nav" in (i.get("properties") or "").split()), None)
    ncx = next((i for i in manifest.values() if i.get("media-type") == "application/x-dtbncx+xml"), None)
    toc = []
    if nav is not None:
        toc = _toc_nav(z, posixpath.normpath(posixpath.join(base, nav.get("href"))))
    if not toc and ncx is not None:
        toc = _toc_ncx(z, posixpath.normpath(posixpath.join(base, ncx.get("href"))))

    # todo el libro como un solo texto, con la posición de cada fichero y ancla
    text_parts, offsets, file_start = [], {}, {}
    pos = 0
    for f in spine:
        p = _Text()
        p.feed(z.read(f).decode("utf-8", "replace"))
        file_start[f] = pos
        for k, v in p.anchors.items():
            offsets[(f, k)] = pos + v
        chunk = "".join(p.parts)
        text_parts.append(chunk)
        pos += len(chunk)
    full = "".join(text_parts)

    # recorte de la cabecera/licencia de Gutenberg
    m1 = re.search(r"\*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG[^\n]*", full)
    m2 = re.search(r"\*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG", full)
    lo, hi = (m1.end() if m1 else 0), (m2.start() if m2 else len(full))

    entries = []
    for e in toc:
        start = offsets.get((e["file"], e["frag"])) if e["frag"] else file_start.get(e["file"])
        if start is None:
            start = file_start.get(e["file"])
        if start is not None:
            entries.append({**e, "start": start})
    entries.sort(key=lambda e: e["start"])
    # fin de cada entrada = inicio de la siguiente (en orden de lectura)
    for i, e in enumerate(entries):
        e["end"] = entries[i + 1]["start"] if i + 1 < len(entries) else len(full)

    kept = []
    for e in entries:
        if not e["leaf"] or SKIP_TITLE.search(e["title"]):
            continue
        s, t = max(e["start"], lo), min(e["end"], hi)
        body = re.sub(r"\n\s*\n+", "\n\n", full[s:t]).strip() if t > s else ""
        if len(body) >= MIN_CHAPTER_CHARS:
            kept.append((e, body))
    first = next((i for i, (e, _) in enumerate(kept) if CHAPTER_TITLE.search(e["title"])), 0)
    chapters = []
    for i, (e, body) in enumerate(kept):
        title = e["title"]
        parent = e.get("parent", "")
        if parent and parent.lower() not in title.lower() and not SKIP_TITLE.search(parent):
            title = f"{parent} · {title}"
        chapters.append({"n": len(chapters) + 1, "title": title, "extra": i < first, "text": body})
    n = 0
    for c in chapters:  # numeración de lectura: solo capítulos, los preliminares quedan en 0
        if not c["extra"]:
            n += 1
        c["n"] = 0 if c["extra"] else n
    return chapters


# ---------- 3. Gemini lee el capítulo ----------
SCHEMA = {
    "type": "object",
    "properties": {
        "niebla": {"type": "string"},
        "pista": {"type": "string"},
        "peligro": {"type": "string"},
        "escena": {
            "type": "object",
            "properties": {
                "nudo": {"type": "string"},
                "desenlace": {"type": "string"},
                "elementos": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "tipo": {"type": "string", "enum": ["objeto", "animal", "criatura", "personaje", "lugar"]},
                            "nombre": {"type": "string"},
                            "detalle": {"type": "string"},
                        },
                        "required": ["tipo", "nombre", "detalle"],
                    },
                },
            },
            "required": ["nudo", "desenlace", "elementos"],
        },
    },
    "required": ["niebla", "pista", "peligro", "escena"],
}

SYSTEM = """Eres el narrador misterioso de un widget para lectores. Te doy el TEXTO REAL de un capítulo.
Escribe tres "pre-spoilers" que despierten las ganas de leerlo sin arruinarlo, y una ficha visual.

Reglas de las leyendas (en {lang}):
- Máximo 40 palabras cada una, nunca más de 50. Evocadoras, con misterio.
- Solo lo que ocurre en ESTE capítulo. Nada de capítulos posteriores ni del final del libro.
- No digas "spoiler", "en este capítulo" ni números de capítulo. Como mucho una pregunta retórica entre las tres.
Niveles:
- niebla: solo atmósfera y emoción. Cero trama.
- pista: un objeto, lugar o personaje clave, sin decir qué pasa con él.
- peligro: insinúa el conflicto central con tensión, sin revelar su resolución.
Ficha visual (solo la lee un ilustrador de pixel art, nunca el lector):
- nudo: el conflicto central, máx. 25 palabras.
- desenlace: cómo se resuelve al final del capítulo, máx. 25 palabras.
- elementos: 3 a 5 cosas CONCRETAS y dibujables del capítulo (tipo, nombre ≤5 palabras, detalle visual ≤20 palabras).
Básate solo en el texto que te doy."""


class QuotaError(RuntimeError):
    pass


def gemini(api_key: str, system: str, user: str, retries: int = 6) -> tuple[dict, str]:
    """Devuelve (respuesta, modelo usado)."""
    body = json.dumps({
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": user}]}],
        "generationConfig": {"responseMimeType": "application/json", "responseSchema": SCHEMA, "temperature": 0.8},
    }).encode()
    for attempt in range(retries):
        model = GEMINI_MODELS[attempt % len(GEMINI_MODELS)]
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
        req = urllib.request.Request(
            url, data=body, headers={**UA, "Content-Type": "application/json", "x-goog-api-key": api_key}
        )
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                out = json.loads(r.read())
            u = out.get("usageMetadata", {})
            print(f"    {model}: tokens in={u.get('promptTokenCount')} out={u.get('candidatesTokenCount')}",
                  file=sys.stderr)
            return json.loads(out["candidates"][0]["content"]["parts"][0]["text"]), model
        except urllib.error.HTTPError as e:
            raw = e.read().decode(errors="replace")
            try:
                detail = json.loads(raw).get("error", {}).get("message", raw)[:300]
            except ValueError:
                detail = raw[:300]
            if e.code in (403, 404, 429) or e.code >= 500:
                if attempt == retries - 1:
                    raise QuotaError(f"HTTP {e.code}: {detail}") from None
                wait = 5 if e.code in (403, 404) else 15 * (attempt // len(GEMINI_MODELS) + 1)
                print(f"    {model}: HTTP {e.code} {detail[:160]} → reintento en {wait}s", file=sys.stderr)
                time.sleep(wait)
                continue
            raise RuntimeError(f"HTTP {e.code}: {detail}") from None
        except (KeyError, IndexError, json.JSONDecodeError) as e:
            if attempt == retries - 1:
                raise RuntimeError(f"respuesta inválida: {e}") from None
    raise RuntimeError("sin respuesta")


def validate(item: dict) -> list[str]:
    errs = []
    for lv in ("niebla", "pista", "peligro"):
        n = len(str(item.get(lv, "")).split())
        if n == 0 or n > MAX_WORDS:
            errs.append(f"{lv}: {n} palabras")
    els = (item.get("escena") or {}).get("elementos") or []
    if not els:
        errs.append("escena sin elementos")
    return errs


# ---------- CLI ----------
def _authors(b: dict) -> str:
    return "; ".join(a["name"] for a in b.get("authors", []))


def cmd_search(a):
    for b in search(a.query, a.lang):
        print(f"{b['id']:>6}  {b['title'][:70]}  — {_authors(b)}  [{','.join(b['languages'])}]")


def _load_chapters(gid: int) -> tuple[dict, list[dict]]:
    meta = book_meta(gid)
    epub_url = meta["formats"].get("application/epub+zip")
    if not epub_url:
        sys.exit("Este libro no tiene EPUB en Gutenberg")
    return meta, epub_chapters(http_get(epub_url, cache_name=f"pg{gid}.epub"))


def cmd_chapters(a):
    meta, chapters = _load_chapters(a.id)
    extra = sum(c["extra"] for c in chapters)
    print(f"{meta['title']} — {_authors(meta)}  ({len(chapters) - extra} capítulos + {extra} preliminares)\n")
    for c in chapters:
        preview = " ".join(c["text"].split())[:90]
        num = "  –" if c["extra"] else f"{c['n']:>3}"
        print(f"{num}. {c['title'][:50]:<50} {len(c['text']):>7} car.  «{preview}…»")


def cmd_pack(a):
    key = os.environ.get("GEMINI_API_KEY", "")
    if not key:
        sys.exit("Falta GEMINI_API_KEY (créala gratis en https://aistudio.google.com/apikey)")
    meta, chapters = _load_chapters(a.id)
    chapters = [c for c in chapters if not c["extra"]]
    lang = a.lang or {"es": "español", "en": "inglés"}.get((meta.get("languages") or ["es"])[0], "español")
    PACKS.mkdir(exist_ok=True)
    out_path = PACKS / f"gutenberg-{a.id}.json"
    pack = json.loads(out_path.read_text()) if out_path.exists() else {}
    done = {c["n"]: c for c in pack.get("chapters", []) if c.get("niebla")}

    pack.update({
        "format": 1,
        "id": wikidata_qid(a.id) or f"gutenberg:{a.id}",
        "title": meta["title"],
        "authors": [{"name": x["name"], "death_year": x.get("death_year")} for x in meta.get("authors", [])],
        "language": (meta.get("languages") or [None])[0],
        "sources": {"gutenberg": a.id, "epub": meta["formats"]["application/epub+zip"]},
        "generator": {"input": "chapter_text"},
    })
    todo = chapters[: a.max] if a.max else chapters
    print(f"{meta['title']}: {len(chapters)} capítulos, genero {len(todo)} (id {pack['id']})", file=sys.stderr)
    result = []
    for c in chapters:
        entry = {"n": c["n"], "title": c["title"], "chars": len(c["text"])}
        if c["n"] in done:
            entry.update({k: done[c["n"]][k] for k in ("niebla", "pista", "peligro", "escena") if k in done[c["n"]]})
        elif c in todo:
            print(f"  cap. {c['n']}: {c['title'][:50]}", file=sys.stderr)
            user = f"Libro: {meta['title']} — {_authors(meta)}\nCapítulo {c['n']}: {c['title']}\n\nTEXTO:\n" \
                   + c["text"][:MAX_CHAPTER_CHARS]
            try:
                item, used = gemini(key, SYSTEM.format(lang=lang), user)
                entry["model"] = used
                errs = validate(item)
                if errs:
                    print(f"    aviso: {errs}", file=sys.stderr)
                entry.update(item)
            except (QuotaError, RuntimeError) as e:
                print(f"    error de Gemini, guardo lo hecho y paro: {e}", file=sys.stderr)
                result.append(entry)
                result += [{"n": x["n"], "title": x["title"], "chars": len(x["text"])} for x in chapters[c["n"]:]]
                break
            if a.delay:
                time.sleep(a.delay)
        result.append(entry)
    pack["chapters"] = result
    out_path.write_text(json.dumps(pack, ensure_ascii=False, indent=2))
    print(f"\n-> {out_path}", file=sys.stderr)
    for e in result:
        if e.get("niebla"):
            print(f"\n## {e['n']}. {e['title']}")
            for lv in ("niebla", "pista", "peligro"):
                print(f"  {lv:>7}: {e[lv]}")
            print(f"  elementos: {', '.join(x['nombre'] for x in e['escena']['elementos'])}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("search")
    s.add_argument("query")
    s.add_argument("--lang", default=None, help="es, en…")
    s.set_defaults(fn=cmd_search)
    c = sub.add_parser("chapters")
    c.add_argument("id", type=int)
    c.set_defaults(fn=cmd_chapters)
    p = sub.add_parser("pack")
    p.add_argument("id", type=int)
    p.add_argument("--max", type=int, default=3, help="capítulos a generar (0 = todos)")
    p.add_argument("--lang", default=None, help="idioma de las leyendas (por defecto, el del libro)")
    p.add_argument("--delay", type=float, default=4, help="segundos entre llamadas (límite por minuto)")
    p.set_defaults(fn=cmd_pack)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
