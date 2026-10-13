#!/usr/bin/env python3
"""Pre-spoilers SIN IA: algoritmos clásicos de extracción sobre el texto real.

- TF-IDF (Salton): lo distintivo de cada capítulo frente al resto del libro (el «corpus» es el propio libro).
- TextRank (Mihalcea y Tarau, 2004): centralidad de frases en un grafo de solapamiento léxico.
- MMR (Carbonell y Goldstein, 1998): diversidad, no elegir dos frases que dicen lo mismo.
- Posición (Edmundson, 1969): el inicio plantea, el final resuelve -> el final nunca se cita.
- Léxicos propios (atmósfera, tensión): pequeños y nuestros, sin licencias restrictivas.

Las leyendas son CITAS del capítulo (un «teaser» como el de una contraportada):
  niebla  = la frase más atmosférica de la primera mitad, sin nombres propios (cero trama);
  pista   = una frase central que menciona lo más distintivo del capítulo (objeto, lugar o personaje);
  peligro = la frase de más tensión del tramo medio (nunca del último 25 %: ahí está la resolución).

  python3 poc/classic.py 61851 1 2 3      # capítulos 1, 2 y 3 de Crimen y castigo
"""
from __future__ import annotations

import math
import os
import re
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(__file__))
import packgen  # noqa: E402
from digest import STOP, WORD, entities, sentences  # noqa: E402

ATMOS = set("""
luz sombra sombras niebla bruma frío fría calor viento lluvia noche oscuridad oscuro oscura silencio olor
olía ruido rumor polvo humo sol luna nubes cielo calle calles nieve hielo fuego llama vela velas gris
pálido pálida amarillo amarillas rojo negra negro blanco blanca húmedo húmeda tibio sofocante bochorno
hedor aire tormenta trueno relámpago crepúsculo madrugada aurora atardecer tarde mañana lejos eco
murmullo susurro brillo resplandor tinieblas penumbra helado helada ardiente polvoriento espeso
light shadow fog mist cold wind rain night dark darkness silence smell snow moon sky fire grey pale
""".split())

TENSION = set("""
muerte muerto muerta morir matar asesinato asesino sangre miedo terror temor horror pánico grito gritó
gritos peligro amenaza crimen golpe hacha cuchillo arma pistola veneno huir huyó escapar desesperación
angustia culpa furia rabia odio celos traición secreto prisión preso cárcel enemigo lucha pelea herida
temblaba temblando temblor estremeció horrible terrible espantoso siniestro fatal ruina deuda locura
fiebre delirio desmayo sospecha acusación juicio policía
death dead kill blood fear terror horror scream danger threat crime knife gun poison escape wolf wolves
""".split())

_NAME_HINT = re.compile(r"\b[A-ZÁÉÍÓÚÑ][a-záéíóúñ]+")


def toks(s: str) -> list[str]:
    return [w.lower() for w in WORD.findall(s)]


def content(ws: list[str]) -> list[str]:
    return [w for w in ws if len(w) > 3 and w not in STOP]


def tfidf_terms(chapter_texts: list[str], i: int, k: int = 12) -> list[str]:
    docs = [Counter(content(toks(t))) for t in chapter_texts]
    n = len(docs)
    df = Counter(w for d in docs for w in d)
    d = docs[i]
    total = sum(d.values()) or 1
    scored = {w: (c / total) * math.log((n + 1) / (df[w] + 0.5)) for w, c in d.items() if c >= 2}
    return [w for w, _ in sorted(scored.items(), key=lambda x: -x[1])[:k]]


def textrank(sents: list[str], iters: int = 30, d: float = 0.85) -> list[float]:
    bags = [set(content(toks(s))) for s in sents]
    n = len(sents)
    # similitud de Mihalcea: solapamiento normalizado por la longitud (log) de ambas frases
    nbrs: list[list[tuple[int, float]]] = [[] for _ in range(n)]
    index: dict[str, list[int]] = {}
    for i, b in enumerate(bags):
        for w in b:
            index.setdefault(w, []).append(i)
    for i, b in enumerate(bags):
        if len(b) < 2:
            continue
        overlap = Counter(j for w in b for j in index[w] if j != i)
        for j, o in overlap.items():
            if len(bags[j]) >= 2:
                nbrs[i].append((j, o / (math.log(len(b) + 1) + math.log(len(bags[j]) + 1))))
    out_w = [sum(w for _, w in nb) or 1.0 for nb in nbrs]
    score = [1.0] * n
    for _ in range(iters):
        score = [(1 - d) + d * sum(score[j] * w / out_w[j] for j, w in nbrs[i]) for i in range(n)]
    return score


def trim(s: str, max_words: int = 40) -> str:
    s = " ".join(s.split()).strip(" —-«»\"")
    words = s.split()
    if len(words) <= max_words:
        return s
    cut = " ".join(words[:max_words])
    # cortar en la última pausa natural para que la cita no quede mutilada
    m = max(cut.rfind(","), cut.rfind(";"), cut.rfind(":"))
    return (cut[:m] if m > len(cut) // 2 else cut).rstrip(",;: ") + "…"


def pick(cands: list[tuple[float, int, str]], used: set[int], sim_bags: list[set[str]], lam: float = 0.7):
    """MMR: relevancia menos parecido con lo ya elegido."""
    best = None
    for sc, i, s in cands:
        if i in used:
            continue
        sim = max((len(sim_bags[i] & sim_bags[j]) / (len(sim_bags[i] | sim_bags[j]) or 1) for j in used), default=0)
        v = lam * sc - (1 - lam) * sim * max(1.0, sc)
        if best is None or v > best[0]:
            best = (v, i, s)
    return best


def captions(text: str, distinctive: list[str], ents: list[str]) -> dict:
    sents = sentences(text)
    n = len(sents)
    tr = textrank(sents)
    bags = [set(content(toks(s))) for s in sents]
    maxtr = max(tr) or 1
    ok = lambda s: 8 <= len(s.split()) <= 70 and not s.startswith(("—", "-", "«"))  # sin diálogo suelto

    def window(a: float, b: float):
        return [i for i in range(int(n * a), int(n * b)) if ok(sents[i])]

    used: set[int] = set()
    out = {}
    # niebla: atmósfera, sin nombres propios, primera mitad
    c = []
    for i in window(0.0, 0.5):
        ws = toks(sents[i])
        names = sum(1 for e in ents if e in sents[i])
        c.append((sum(w in ATMOS for w in ws) / len(ws) ** 0.5 - 0.6 * names + 0.1 * tr[i] / maxtr, i, sents[i]))
    best = pick(c, used, bags)
    if best:
        used.add(best[1]); out["niebla"] = trim(best[2])
    # pista: centralidad + lo distintivo del capítulo, primeros dos tercios
    top = set(distinctive[:6])
    c = [(tr[i] / maxtr + 0.8 * len(top & bags[i]) + 0.5 * sum(e in sents[i] for e in ents[:3]), i, sents[i]) for i in window(0.0, 0.67)]
    best = pick(c, used, bags)
    if best:
        used.add(best[1]); out["pista"] = trim(best[2])
    # peligro: tensión en el tramo medio, nunca el último cuarto
    c = []
    for i in window(0.25, 0.75):
        ws = toks(sents[i])
        c.append((sum(w in TENSION for w in ws) * 1.5 + sents[i].count("!") * 0.5 + 0.3 * tr[i] / maxtr, i, sents[i]))
    best = pick(c, used, bags)
    if best:
        used.add(best[1]); out["peligro"] = trim(best[2])
    return out


VERBISH = ("aba", "aban", "ían", "ando", "iendo", "ado", "ada", "ido", "ida", "mente", "ría", "rían", "ase", "iese")


def scene(distinctive: list[str], ents: list[str]) -> list[str]:
    """Candidatos dibujables para el sprite: nombres propios + términos distintivos con pinta de sustantivo."""
    nouns = [w for w in distinctive if not w.endswith(VERBISH) and not (w.endswith(("ar", "er", "ir")) and len(w) > 5)]
    return ents[:3] + nouns[:5]


def main():
    gid = int(sys.argv[1])
    wanted = [int(x) for x in sys.argv[2:]] or [1]
    meta, chapters = packgen._load_chapters(gid)
    chapters = [c for c in chapters if not c["extra"]]
    texts = [c["text"] for c in chapters]
    for n in wanted:
        ch = chapters[n - 1]
        sents = sentences(ch["text"])
        ents = [e for e, _ in entities(sents).most_common(8)]
        dist = tfidf_terms(texts, n - 1)
        caps = captions(ch["text"], dist, ents)
        print(f"\n## {n}. {ch['title']}  ({len(sents)} frases)")
        print(f"  distintivo (TF-IDF): {', '.join(dist[:10])}")
        print(f"  personajes/lugares: {', '.join(ents)}")
        for lv in ("niebla", "pista", "peligro"):
            print(f"  {lv:>7}: {caps.get(lv, '—')}")
        print(f"  para el dibujo: {', '.join(scene(dist, ents))}")


if __name__ == "__main__":
    main()
