#!/usr/bin/env python3
"""Narrador LOCAL: una IA pequeña en el dispositivo reescribe la cita elegida por los algoritmos clásicos.

  cita (classic.py, sin destripar) -> modelo local (Qwen2.5-1.5B) -> validador clásico -> si falla, la cita

Prueba en el Mac con LM Studio (mismo modelo que irá al móvil):
  lms server start && lms load qwen2.5-1.5b-instruct
  python3 poc/narrator.py 61851 1 2 3
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
import packgen  # noqa: E402
from classic import captions, tfidf_terms  # noqa: E402
from digest import STOP, entities, sentences  # noqa: E402

URL = os.environ.get("LOCAL_LLM", "http://localhost:1234/v1/chat/completions")
KEY = os.environ.get("LLM_KEY", "")  # proveedores en la nube con API compatible con OpenAI (Groq, Mistral, Gemini…)
HEADERS = {"Content-Type": "application/json", "User-Agent": "book-widget", **({"Authorization": f"Bearer {KEY}"} if KEY else {})}
MODEL = os.environ.get("LOCAL_MODEL", "qwen2.5-1.5b-instruct")

SYSTEM = (
    "Eres el narrador misterioso de un juego para lectores. Recibes una CITA de un libro y la reescribes "
    "en español como un susurro intrigante que dé ganas de seguir leyendo. Reglas: máximo 30 palabras; "
    "una o dos frases; usa SOLO las cosas, personas y lugares que aparecen en la cita, sin añadir objetos, "
    "gestos, lugares ni nombres nuevos; no copies la cita; no digas «capítulo», «spoiler» ni «cita»; "
    "sin comillas. Responde solo con el texto.\n\n"
    "Ejemplo:\nCITA: El viejo farero encendió la lámpara mientras el mar golpeaba las rocas.\n"
    "Una lámpara se enciende sobre las rocas; el mar golpea, insistente, como si quisiera entrar."
)
# palabras de tono que el narrador puede añadir sin inventar hechos
MOOD = set("""susurro susurra susurran sombra sombras misterio secreto secretos algo alguien aguarda aguardan espera esperan
quizá quizás pronto nadie silencio silencioso silenciosa pesa pesan late laten acecha acechan umbral cada mientras
aunque oculto oculta ocultos tiempo momento instante todavía nunca siempre como cuando donde aquí allí ahora
insistente lento lenta despacio callado callada inquieto inquieta extraño extraña cierto cierta otra otro
embargo entre parece parecía parecen podría puede quizá acaso apenas casi solo sólo tanto demasiado
misterioso misteriosa misteriosos inquietante oscuro oscura oscuros profundo profunda frío fría lejano lejana
pronto tarde noche vida mundo destino promesa pregunta respuesta verdad mentira miedo temor duda deseo
susurrando esperando aguardando ocurrirá ocurre pasa pasará viene vendrá queda quedan""".split())
TONE = {
    "niebla": "Tono: solo atmósfera y sensaciones, sin contar qué pasa.",
    "pista": "Tono: señala el objeto, lugar o persona de la cita como algo que importa, sin explicar por qué.",
    "peligro": "Tono: tensión y amenaza, como si algo estuviera a punto de torcerse.",
}
WORD = re.compile(r"[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+")
EN_HINT = {"the", "and", "of", "to", "was", "his", "her", "with", "that", "were", "had", "from", "which"}
ES_HINT = {"el", "la", "de", "que", "y", "en", "un", "una", "los", "las", "se", "su", "con", "por", "del", "lo", "al"}


# Variante «susurro»: comprimir (lo que mejor hacen los modelos pequeños), no escribir libremente.
# Los ejemplos van como turnos de conversación: los modelos pequeños los siguen mejor que en el sistema.
WHISPER = (
    "Conviertes una CITA de un libro en un SUSURRO: una sola frase corta (de 8 a 20 palabras), en español y en "
    "presente, hecha con las palabras importantes de la cita. No añadas nada que no esté en la cita. "
    "Responde solo con el susurro."
)
SHOTS = {
    "niebla": ("La niebla cubría el puerto y las farolas apenas alumbraban los barcos dormidos.",
               "Niebla sobre el puerto; las farolas apenas alumbran barcos dormidos."),
    "pista": ("El capitán guardó el mapa en el cofre y lo cerró con una llave de hierro.",
              "Un mapa, un cofre y una llave de hierro que el capitán no suelta."),
    "peligro": ("Oyó pasos en la escalera y apagó la vela con la mano temblorosa.",
                "Pasos en la escalera. Una vela se apaga. La mano tiembla."),
}
PROMPT = os.environ.get("PROMPT", "susurro")  # reescribir | susurro | vocabulario | pasaje

PASSAGE = (
    "Eres el narrador misterioso de un juego para lectores. Te doy un PASAJE real de un libro. Escribe una leyenda "
    "que despierte las ganas de leerlo: 2 o 3 frases, entre 30 y 45 palabras, en español y en presente, con tono "
    "evocador. Usa SOLO personas, objetos, lugares y hechos que aparecen en el pasaje; no inventes nada y no reveles "
    "cómo termina. No digas «capítulo», «spoiler» ni «pasaje». Responde solo con la leyenda."
)
PASSAGE_SHOTS = {
    "niebla": ("La niebla cubría el puerto desde el amanecer. Las farolas apenas alumbraban los barcos dormidos, y el agua "
               "golpeaba los pilotes con un rumor lento. Nadie caminaba por el muelle; solo un perro buscaba algo entre las redes.",
               "La niebla se traga el puerto desde el amanecer. Las farolas apenas alumbran barcos dormidos mientras el agua "
               "golpea los pilotes con un rumor lento. En el muelle vacío, solo un perro busca algo entre las redes."),
    "pista": ("El capitán guardó el mapa en el cofre y lo cerró con una llave de hierro. Después colgó la llave de su cuello "
              "y subió a cubierta sin decir palabra. Los marineros se miraron: nadie había visto antes aquel mapa.",
              "Un mapa que nadie había visto antes. Un cofre cerrado con una llave de hierro que ahora cuelga del cuello del "
              "capitán. Él sube a cubierta en silencio, y los marineros se miran sin atreverse a preguntar."),
    "peligro": ("Oyó pasos en la escalera y apagó la vela con la mano temblorosa. Los pasos se detuvieron frente a su puerta. "
                "Contuvo el aliento; alguien probaba el picaporte, muy despacio.",
                "Pasos en la escalera. Una vela que se apaga con mano temblorosa. Frente a la puerta, los pasos se detienen, "
                "y alguien prueba el picaporte, muy despacio, mientras ella contiene el aliento."),
}


def passage(text: str, quote: str, before: int = 1, after: int = 2) -> str:
    """La cita y las frases que la rodean: más material real para una leyenda más larga."""
    sents = sentences(text)
    probe = quote.rstrip("…").strip()[:40]
    for i, s_ in enumerate(sents):
        if probe and probe in s_:
            return " ".join(sents[max(0, i - before): i + after + 1])
    return quote


def messages(level: str, quote: str) -> list[dict]:
    if PROMPT == "pasaje":
        msgs = [{"role": "system", "content": PASSAGE}]
        for lv in [x for x in ("niebla", "pista", "peligro") if x != level] + [level]:
            msgs += [{"role": "user", "content": f"PASAJE: {PASSAGE_SHOTS[lv][0]}"}, {"role": "assistant", "content": PASSAGE_SHOTS[lv][1]}]
        msgs.append({"role": "user", "content": f"PASAJE: {quote} /no_think"})
        return msgs
    if PROMPT == "reescribir":
        return [{"role": "system", "content": SYSTEM}, {"role": "user", "content": f"{TONE[level]}\nCITA: {quote} /no_think"}]
    msgs = [{"role": "system", "content": WHISPER}]
    for lv in ("niebla", "pista", "peligro"):  # tres ejemplos, el del nivel pedido el último
        if lv != level:
            msgs += [{"role": "user", "content": f"CITA: {SHOTS[lv][0]}"}, {"role": "assistant", "content": SHOTS[lv][1]}]
    msgs += [{"role": "user", "content": f"CITA: {SHOTS[level][0]}"}, {"role": "assistant", "content": SHOTS[level][1]}]
    extra = ""
    if PROMPT == "vocabulario":
        keep = [w for w in dict.fromkeys(WORD.findall(quote)) if len(w) > 3 and w.lower() not in STOP][:14]
        extra = f"\nPALABRAS QUE PUEDES USAR: {', '.join(keep)}"
    msgs.append({"role": "user", "content": f"CITA: {quote}{extra} /no_think"})
    return msgs


def translate(quote: str) -> str:
    """Paso previo para libros en otro idioma: traducción fiel (los modelos pequeños lo hacen bien)."""
    body = {"model": MODEL, "temperature": 0.1, "max_tokens": 400 if "groq.com" in URL else 160, **({"reasoning_format": "hidden", "reasoning_effort": "none" if "qwen" in MODEL else "low"} if "groq.com" in URL else {}), "messages": [
        {"role": "system", "content": "Traduce al español el texto del usuario, de forma fiel y natural. Responde solo con la traducción."},
        {"role": "user", "content": quote + " /no_think"}]}
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers=HEADERS)
    text = json.loads(urllib.request.urlopen(req, timeout=180).read())["choices"][0]["message"]["content"]
    return " ".join(re.sub(r"<think>.*?</think>|</?answer>|/no_think", "", text, flags=re.S).split()).strip('"«»“” ')


def ask(level: str, quote: str) -> str:
    body = {"model": MODEL, "temperature": 0.5, "max_tokens": 160 if PROMPT == "pasaje" else 90, "messages": messages(level, quote)}
    if "groq.com" in URL:  # modelos que razonan: que piensen poco y no devuelvan el razonamiento
        body.update(max_tokens=400, reasoning_format="hidden", reasoning_effort="none" if "qwen" in MODEL else "low")
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers=HEADERS)
    out = json.loads(urllib.request.urlopen(req, timeout=180).read())
    text = out["choices"][0]["message"]["content"]
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)  # modelos con «modo pensar» (Qwen3)
    text = re.sub(r"</?answer>", "", text)  # Hunyuan envuelve la respuesta
    text = text.replace("/no_think", "")
    text = re.sub(r"^(tono|cita|respuesta|narrador)\s*:[^.]*[.:]\s*", "", text.strip(), flags=re.I)  # eco de la instrucción
    return " ".join(text.split()).strip().strip('"«»“”')


def stem(w: str) -> str:
    import unicodedata
    w = "".join(c for c in unicodedata.normalize("NFD", w.lower()) if unicodedata.category(c) != "Mn")
    return w[:5]


def validate(text: str, quote: str, known: set[str]) -> str | None:
    """None si vale; si no, el motivo. Reglas clásicas, baratas y deterministas."""
    words = text.split()
    lo, hi = (20, 55) if PROMPT == "pasaje" else (5, 40)
    if not lo <= len(words) <= hi:
        return f"{len(words)} palabras"
    low = text.lower()
    if any(w in low for w in ("capítulo", "spoiler", "cita", "narrador")):
        return "palabra prohibida"
    toks = [w.lower() for w in WORD.findall(text)]
    if sum(t in EN_HINT for t in toks) >= 2:
        return "en inglés"
    # anclaje: lo que nombra el texto debe salir de la cita (raíz de 5 letras), salvo palabras de tono
    qstems = {stem(w) for w in WORD.findall(quote)} | {stem(w) for k in known for w in k.split()}
    content = [w for w in toks if len(w) > 3 and w not in STOP and w not in MOOD]
    loose = [w for w in content if stem(w) not in qstems]
    if content and len(loose) / len(content) > 0.4:
        return f"inventa: {', '.join(loose[:4])}"
    # comprimir con las mismas palabras está bien; copiar casi entera la cita, no
    qset = {w.lower() for w in WORD.findall(quote)}
    if len(words) >= 0.85 * len(quote.split()) and len(set(toks) & qset) / max(1, len(set(toks) | qset)) > 0.7:
        return "copia la cita"
    # nombres propios nuevos (mayúscula fuera de inicio de frase) = posible invención
    allowed = {w.lower() for w in WORD.findall(quote)} | {w.lower() for k in known for w in k.split()}
    for i, w in enumerate(WORD.findall(text)):
        prev = text[: text.find(w)].rstrip()
        sentence_start = i == 0 or prev.endswith((".", "!", "?", "¡", "¿", "…"))
        if w[0].isupper() and not sentence_start and w.lower() not in allowed and w.lower() not in STOP:
            return f"nombre nuevo: {w}"
    return None


def compare(cases: list[tuple[str, str, set[str]]], models: list[str]):
    """Carga cada modelo en LM Studio y mide: % aceptado por el validador, tiempo medio y ejemplos."""
    import subprocess
    global MODEL
    lms = os.path.expanduser("~/.lmstudio/bin/lms")
    rows = []
    for m in models:
        r = subprocess.CompletedProcess([], 0)
        if "localhost" in URL:
            subprocess.run([lms, "unload", "--all"], capture_output=True)
            r = subprocess.run([lms, "load", m, "--context-length", "2048", "-y"], capture_output=True, text=True)
        if r.returncode != 0:
            print(f"{m}: no carga ({r.stderr.strip()[-120:]})")
            continue
        MODEL = m
        ok, times, samples, reasons = 0, [], [], []
        by_lang: dict[str, list[int]] = {}
        for lv, q, known, lang in cases:
            t0 = time.time()
            try:
                if lang != "es" and os.environ.get("TRANSLATE", "1") == "1":
                    q = translate(q)  # se valida contra la traducción
                cand = ask(lv, q)
                if "localhost" not in URL:
                    time.sleep(4)  # límite por minuto de los niveles gratuitos
            except Exception as e:  # noqa: BLE001
                cand, why = "", f"error {e}"
            else:
                why = validate(cand, q, known)
            times.append(time.time() - t0)
            ok += why is None
            by_lang.setdefault(lang, [0, 0])[0] += why is None
            by_lang[lang][1] += 1
            (samples if why is None else reasons).append(f"[{lv}] {cand}" if why is None else f"[{lv}] {why}: {cand[:90]}")
        rows.append((m, ok, len(cases), sum(times) / len(times), " · ".join(f"{k} {a}/{b}" for k, (a, b) in by_lang.items())))
        print(f"\n### {m}: {ok}/{len(cases)} aceptadas, {sum(times) / len(times):.1f}s de media")
        for x in samples[:4]:
            print("   ✓", x)
        for x in reasons[:3]:
            print("   ✗", x)
    print("\n| modelo | aceptadas | por idioma | s/leyenda |\n|---|---|---|---|")
    for m, ok, n, t, langs in sorted(rows, key=lambda r: -r[1]):
        print(f"| {m} | {ok}/{n} | {langs} | {t:.1f} |")


def build_cases(spec: list[tuple[int, list[int]]]):
    cases = []
    for gid, nums in spec:
        meta, chs = packgen._load_chapters(gid)
        chs = [c for c in chs if not c["extra"]]
        texts = [c["text"] for c in chs]
        for n in nums:
            t = chs[n - 1]["text"]
            ents = [e for e, _ in entities(sentences(t)).most_common(8)]
            for lv, q in captions(t, tfidf_terms(texts, n - 1), ents).items():
                if PROMPT == "pasaje":
                    q = passage(t, q)
                cases.append((lv, q, set(ents), (meta.get("languages") or ["es"])[0]))
    return cases


def main():
    if sys.argv[1] == "compare":
        compare(build_cases([(61851, [1, 2, 3, 4]), (345, [1, 2])]), sys.argv[2:])
        return
    gid, wanted = int(sys.argv[1]), [int(x) for x in sys.argv[2:]] or [1]
    meta, chs = packgen._load_chapters(gid)
    chs = [c for c in chs if not c["extra"]]
    texts = [c["text"] for c in chs]
    for n in wanted:
        t = chs[n - 1]["text"]
        ents = [e for e, _ in entities(sentences(t)).most_common(8)]
        caps = captions(t, tfidf_terms(texts, n - 1), ents)
        print(f"\n## {n}. {chs[n - 1]['title']}")
        for lv in ("niebla", "pista", "peligro"):
            q = caps.get(lv)
            if not q:
                continue
            t0 = time.time()
            out, why = None, None
            for _ in range(2):
                cand = ask(lv, q)
                why = validate(cand, q, set(ents))
                if why is None:
                    out = cand
                    break
            print(f"  {lv:>7} cita : {q}")
            print(f"  {'':>7} local: {out if out else '— (rechazado: ' + why + ' -> se usa la cita)'}  [{time.time() - t0:.1f}s]")


if __name__ == "__main__":
    main()
