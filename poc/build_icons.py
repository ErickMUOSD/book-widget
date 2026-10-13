#!/usr/bin/env python3
"""Prepara (una sola vez) la biblioteca de iconos para los dibujos SIN IA.

Fuente: https://github.com/game-icons/icons (CC BY 3.0, algunos CC0; atribución en ICONS_CREDITS.txt).
Salida en android/app/src/main/assets/icons/:
  masks.bin   4239 × 128 bytes: máscara 32×32 (1 bit por píxel, filas de 4 bytes)
  icons.json  [{"name": "pocket-watch", "author": "lorc", "es": ["reloj de bolsillo", ...]}, ...]

  uv run --with cairosvg --with pillow python poc/build_icons.py rasterize <repo>
  GEMINI_API_KEY=... python3 poc/build_icons.py translate      # nombres -> palabras clave en español
"""
from __future__ import annotations

import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "android/app/src/main/assets/icons"
SIZE = 32


def rasterize(repo: str):
    import cairosvg
    from PIL import Image

    OUT.mkdir(parents=True, exist_ok=True)
    icons, blob = [], bytearray()
    skipped = 0
    for svg in sorted(Path(repo).glob("*/*.svg")):
        if svg.parent.name == "badges":  # flechas y símbolos de interfaz, no objetos
            continue
        png = cairosvg.svg2png(bytestring=svg.read_bytes(), output_width=128, output_height=128)
        img = Image.open(io.BytesIO(png)).convert("L").resize((SIZE, SIZE), Image.BOX)
        px = img.load()
        bits = [[1 if px[x, y] > 110 else 0 for x in range(SIZE)] for y in range(SIZE)]
        filled = sum(map(sum, bits))
        if not 60 <= filled <= 800:  # casi vacío o casi lleno: no se lee a 32 px
            skipped += 1
            continue
        for row in bits:
            v = 0
            for b in row:
                v = (v << 1) | b
            blob += v.to_bytes(4, "big")
        icons.append({"name": svg.stem, "author": svg.parent.name})
    (OUT / "masks.bin").write_bytes(bytes(blob))
    (OUT / "icons.json").write_text(json.dumps(icons, ensure_ascii=False))
    authors = sorted({i["author"] for i in icons})
    (OUT / "ICONS_CREDITS.txt").write_text(
        "Iconos de game-icons.net (https://game-icons.net), licencia CC BY 3.0 "
        "(https://creativecommons.org/licenses/by/3.0/) o CC0 según autor.\n"
        "Autores: " + ", ".join(authors) + "\nModificados: rasterizados a 32×32 y coloreados en la app.\n"
    )
    print(f"{len(icons)} iconos ({skipped} descartados), {len(blob) // 1024} KB")


TRANSLATE_PROMPT = """Te doy nombres de iconos en inglés (separados por guiones). Para cada uno devuelve de 1 a 4
palabras clave en español: sustantivos en singular y minúsculas, sin artículos, que un texto en español usaría
para nombrar eso (sinónimos incluidos). Ejemplo: "pocket-watch" -> ["reloj", "reloj de bolsillo"];
"wolf-head" -> ["lobo"]; "candle-light" -> ["vela", "candela"]. Si es abstracto o un símbolo de juego, devuelve []."""

SCHEMA = {"type": "array", "items": {"type": "object", "properties": {
    "name": {"type": "string"}, "es": {"type": "array", "items": {"type": "string"}}}, "required": ["name", "es"]}}

MODELS = ["gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3-flash-preview",
          "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-flash-lite-latest"]


def translate():
    key = os.environ["GEMINI_API_KEY"]
    icons = json.loads((OUT / "icons.json").read_text())
    todo = sorted({i["name"] for i in icons if "es" not in i})
    done: dict[str, list[str]] = {i["name"]: i["es"] for i in icons if "es" in i}
    dead: set[str] = set()
    batch = 250
    for k in range(0, len(todo), batch):
        names = todo[k:k + batch]
        for attempt in range(10):
            pool = [m for m in MODELS if m not in dead]
            if not pool:
                sys.exit("sin cuota en ningún modelo; se reanuda mañana (lo hecho está guardado)")
            model = pool[attempt % len(pool)]
            body = {"systemInstruction": {"parts": [{"text": TRANSLATE_PROMPT}]},
                    "contents": [{"role": "user", "parts": [{"text": "\n".join(names)}]}],
                    "generationConfig": {"responseMimeType": "application/json", "responseSchema": SCHEMA}}
            req = urllib.request.Request(
                f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "x-goog-api-key": key})
            try:
                out = json.loads(urllib.request.urlopen(req, timeout=300).read())
                items = json.loads(out["candidates"][0]["content"]["parts"][0]["text"])
                for it in items:
                    if it.get("name") in names:
                        done[it["name"]] = [w.strip().lower() for w in it.get("es", []) if w.strip()][:4]
                print(f"{k + len(names)}/{len(todo)} con {model}", file=sys.stderr)
                break
            except urllib.error.HTTPError as e:
                raw = e.read().decode(errors="replace")
                if e.code == 429 and "PerDay" in raw:
                    dead.add(model)
                    continue
                print(f"  {model}: HTTP {e.code}, reintento", file=sys.stderr)
                time.sleep(15)
            except (KeyError, ValueError) as e:
                print(f"  {model}: respuesta inválida ({e}), reintento", file=sys.stderr)
        for i in icons:
            if i["name"] in done:
                i["es"] = done[i["name"]]
        (OUT / "icons.json").write_text(json.dumps(icons, ensure_ascii=False))
    print(f"traducidos {sum(1 for i in icons if i.get('es'))} de {len(icons)}")


if __name__ == "__main__":
    {"rasterize": lambda: rasterize(sys.argv[2]), "translate": translate}[sys.argv[1]]()
