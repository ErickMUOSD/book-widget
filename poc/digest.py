#!/usr/bin/env python3
"""Preprocesado del capítulo SIN IA: el modelo recibe solo lo necesario, ya filtrado.

- Personajes y lugares: nombres propios (mayúscula fuera de inicio de frase), con nº de menciones.
- Palabras clave: frecuentes, sin palabras vacías.
- Pasajes clave: las frases con más peso (palabras clave + nombres), más el comienzo y el final del
  capítulo (de ahí salen el nudo y el desenlace), en el orden original.

  python3 poc/digest.py 61851 3            # muestra el extracto del capítulo 3
  GEMINI_API_KEY=... python3 poc/digest.py 61851 3 --compare   # extracto vs. texto completo
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(__file__))
import packgen  # noqa: E402

STOP = set("""
a al algo algunas algunos ante antes aquel aquella aquellas aquello aquellos aqui aquí asi así aun aún
bajo bien cada casi como cómo con contra cual cuál cuando cuándo de del desde donde dónde dos el él ella
ellas ello ellos en entre era eran eres es esa esas ese eso esos esta está estaba estaban estado estar
estas este esto estos estoy fue fueron fui ha había habían haber habia hace hacia hasta hay he la las le
les lo los mas más me mi mí mis mismo mucho muy nada ni no nos nosotros o os otra otro otros para pero
poco por porque que qué quien quién se sea ser si sí sido siempre sin sino sobre su sus tal también tan
tanto te tenía tener ti tiene todo todos tras tu tú tus un una uno unos usted ustedes ya yo vez veces
dijo decía dice luego entonces pues después ahora aquel cual cuales mientras durante ese tenia habia
señor señora don doña cosa cosas día días hombre mujer parte vez nunca tiempo
the a an and of to in is was he she it that his her had with for as at by on not be but they you i him
""".split())

SENT = re.compile(r"(?<=[.!?…»])\s+(?=[—\-«\"¿¡A-ZÁÉÍÓÚÑ])")
WORD = re.compile(r"[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+")


def sentences(text: str) -> list[str]:
    # algunas ediciones cortan cada línea del párrafo: un salto simple es un espacio
    text = re.sub(r"(?<!\n)\n(?!\n)", " ", text)
    out = []
    for para in re.split(r"\n\s*\n", text):
        out += [s.strip() for s in SENT.split(" ".join(para.split())) if len(s.strip()) > 1]
    return out


def entities(sents: list[str]) -> Counter:
    """Secuencias de palabras con mayúscula que no abren frase («Catalina Ivanovna»)."""
    c: Counter = Counter()
    for s in sents:
        words = WORD.findall(s)
        i = 1  # la primera palabra de la frase siempre va en mayúscula: no cuenta
        while i < len(words):
            w = words[i]
            if w[0].isupper() and w.lower() not in STOP and len(w) > 2:
                j = i
                while j + 1 < len(words) and words[j + 1][0].isupper() and words[j + 1].lower() not in STOP:
                    j += 1
                c[" ".join(words[i:j + 1])] += 1
                i = j + 1
            else:
                i += 1
    return Counter({k: v for k, v in c.items() if v >= 2})


def digest(text: str, max_chars: int = 4000) -> dict:
    sents = sentences(text)
    ents = entities(sents)
    words = [w.lower() for w in WORD.findall(text)]
    freq = Counter(w for w in words if len(w) > 3 and w not in STOP)
    ent_words = {w.lower() for e in ents for w in e.split()}
    keywords = [w for w, _ in freq.most_common(40) if w not in ent_words][:15]
    top_ents = [e for e, _ in ents.most_common(12)]

    def score(s: str) -> float:
        ws = [w.lower() for w in WORD.findall(s)]
        if len(ws) < 6:
            return 0.0
        tf = sum(freq[w] for w in ws if w in freq) / (len(ws) ** 0.7)
        return tf + 8 * sum(1 for e in top_ents if e in s)

    head = sents[:3]
    tail = sents[-4:]
    middle = list(enumerate(sents[3:-4], start=3))
    ranked = sorted(middle, key=lambda x: score(x[1]), reverse=True)
    budget = max_chars - sum(map(len, head + tail))
    chosen = []
    for i, s in ranked:
        if len(s) > 400:
            s = s[:400] + "…"
        if budget - len(s) < 0:
            continue
        chosen.append((i, s))
        budget -= len(s)
    chosen.sort()
    return {
        "personajes_lugares": [f"{e} ({ents[e]})" for e in top_ents],
        "palabras_clave": keywords,
        "comienzo": " ".join(head),
        "pasajes": [s for _, s in chosen],
        "final": " ".join(tail),
    }


def render(d: dict) -> str:
    return "\n".join([
        "Personajes y lugares (menciones): " + ", ".join(d["personajes_lugares"]),
        "Palabras clave: " + ", ".join(d["palabras_clave"]),
        "COMIENZO: " + d["comienzo"],
        "PASAJES CLAVE (en orden):",
        *[f"- {s}" for s in d["pasajes"]],
        "FINAL: " + d["final"],
    ])


DIGEST_SYSTEM = packgen.SYSTEM.replace(
    "Te doy el TEXTO REAL de un capítulo.",
    "Te doy un EXTRACTO preparado de un capítulo: nombres propios, palabras clave, comienzo, pasajes clave y final.",
).replace("Básate solo en el texto que te doy.", "Básate solo en el extracto: no inventes hechos que no aparezcan en él.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("id", type=int)
    ap.add_argument("chapter", type=int)
    ap.add_argument("--compare", action="store_true")
    a = ap.parse_args()
    meta, chapters = packgen._load_chapters(a.id)
    ch = [c for c in chapters if not c["extra"]][a.chapter - 1]
    d = render(digest(ch["text"]))
    print(f"# {ch['title']}: {len(ch['text'])} car. -> extracto {len(d)} car. ({len(d) / len(ch['text']):.0%})\n")
    print(d)
    if a.compare:
        key = os.environ["GEMINI_API_KEY"]
        head = f"Libro: {meta['title']} — {packgen._authors(meta)}\nCapítulo {a.chapter}: {ch['title']}\n\n"
        for name, system, body in (("COMPLETO", packgen.SYSTEM.format(lang="español"), "TEXTO:\n" + ch["text"]),
                                   ("EXTRACTO", DIGEST_SYSTEM.format(lang="español"), "EXTRACTO:\n" + d)):
            out, model = packgen.gemini(key, system, head + body)
            print(f"\n## {name} ({model})")
            for lv in ("niebla", "pista", "peligro"):
                print(f"  {lv:>7}: {out[lv]}")
            print(f"  nudo: {out['escena']['nudo']}\n  desenlace: {out['escena']['desenlace']}")
            print("  elementos: " + ", ".join(e["nombre"] for e in out["escena"]["elementos"]))


if __name__ == "__main__":
    main()
