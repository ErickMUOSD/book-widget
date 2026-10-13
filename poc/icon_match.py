#!/usr/bin/env python3
"""Simula la elección de icono SIN IA (misma lógica que IconSprites.kt) para revisar resultados.
  python3 poc/icon_match.py 61851 1 2 3 4 5"""
import json, os, sys, unicodedata
sys.path.insert(0, os.path.dirname(__file__))
import packgen
from classic import tfidf_terms, scene
from digest import entities, sentences

ICONS = json.load(open(os.path.join(os.path.dirname(__file__), "../android/app/src/main/assets/icons/icons.json")))

def norm(s):
    w = "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn").split(" ")[0]
    if len(w) > 4 and w.endswith("es") and w[-3] not in "aeiou": w = w[:-2]
    elif len(w) > 3 and w.endswith("s"): w = w[:-1]
    return w

index, fam = {}, {}
for i, ic in enumerate(ICONS):
    fam[ic["name"].split("-")[0]] = fam.get(ic["name"].split("-")[0], 0) + 1
    for k in ic.get("es", []): index.setdefault(norm(k), []).append(i)

def choose(words, used):
    for w in words:
        key = norm(w); hits = [i for i in index.get(key, []) if ICONS[i]["name"] not in used]
        if not hits: continue
        def cost(i):
            es = ICONS[i].get("es", [])
            return (0 if es and norm(es[0]) == key else 2) + (1 if es and " " in es[0] else 0) + (3 if fam[ICONS[i]["name"].split("-")[0]] > 8 else 0)
        best = min(map(cost, hits)); top = [i for i in hits if cost(i) == best]
        return w, [ICONS[i]["name"] for i in top][:4]
    return None, []

meta, chs = packgen._load_chapters(int(sys.argv[1])); chs = [c for c in chs if not c["extra"]]
texts = [c["text"] for c in chs]; used = set()
for n in map(int, sys.argv[2:]):
    t = chs[n - 1]["text"]; dist = tfidf_terms(texts, n - 1); ents = [e for e, _ in entities(sentences(t)).most_common(8)]
    words = scene(dist, ents)
    w, names = choose(words[3:] + words[:3], used)  # sustantivos primero, nombres propios después
    if names: used.add(names[0])
    print(f"{n:>3}. palabras: {', '.join(words[3:8])}  ->  {w}: {names}")
