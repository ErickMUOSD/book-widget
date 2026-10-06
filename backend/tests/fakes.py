from app.services.claude_client import BookContext, ClaudeClient


def good_sprite(name="Bibliotecario", frames=True) -> dict:
    rows = ["." * 32 for _ in range(32)]
    for y in range(8, 26):
        rows[y] = "." * 10 + "1" * 12 + "." * 10
    rows[12] = "." * 10 + "11" + "0" * 2 + "1" * 4 + "0" * 2 + "11" + "." * 10
    return {
        "name": name,
        "palette": ["#101010", "#7755cc", "#ffffff"],
        "pixels": rows,
        "frames": [[[12, 12, 1], [13, 12, 1]], [[10, 8, -1]]] if frames else [],
    }


class FakeClaude(ClaudeClient):
    """Sustituye todas las llamadas a la API y cuenta cuántas se hacen."""

    def __init__(self, total=25, known=True, genre="fantasía", bad_words_for=(), sprite_outputs=None):
        self.total, self.known, self.genre = total, known, genre
        self.bad_words_for = set(bad_words_for)  # capítulos cuya primera leyenda excede 50 palabras
        self.sprite_outputs = list(sprite_outputs or [])
        self.calls = {"index": 0, "sync": 0, "submit": 0, "collect": 0, "sprite": 0}
        self.sprite_prompts: list[dict] = []
        self.batches: dict[str, list[tuple[int, int]]] = {}
        self.batch_state = "in_progress"
        self.sync_attempts: dict[int, int] = {}

    def _items(self, first, last, first_pass=True):
        out = []
        for n in range(first, last + 1):
            long = " ".join(["palabra"] * 60)
            bad = n in self.bad_words_for and first_pass
            out.append({
                "chapter": n,
                "niebla": long if bad else f"Niebla del capítulo {n}",
                "pista": f"Pista del capítulo {n}",
                "peligro": f"Peligro del capítulo {n}",
                "escena": {
                    "nudo": f"Nudo del capítulo {n}",
                    "desenlace": f"Desenlace del capítulo {n}",
                    "elementos": [
                        {"tipo": "objeto", "nombre": f"Objeto {n}", "detalle": "brilla"},
                        {"tipo": "animal", "nombre": f"Animal {n}", "detalle": "nada"},
                    ],
                },
            })
        return out

    def index(self, title, author, synopsis, total_hint):
        self.calls["index"] += 1
        if not self.known:
            return {"known": False, "genre": "otro", "chapters": []}
        return {"known": True, "genre": self.genre,
                "chapters": [{"n": i, "title": f"Cap {i}"} for i in range(1, self.total + 1)]}

    def spoilers_sync(self, ctx: BookContext, first, last):
        self.calls["sync"] += 1
        retry = self.sync_attempts.get(first, 0) > 0
        self.sync_attempts[first] = self.sync_attempts.get(first, 0) + 1
        return self._items(first, last, first_pass=not retry)

    def spoilers_batch_submit(self, ctx, blocks):
        self.calls["submit"] += 1
        bid = f"batch_{self.calls['submit']}"
        self.batches[bid] = blocks
        return bid

    def batch_status(self, batch_id):
        return self.batch_state

    def spoilers_batch_collect(self, batch_id):
        self.calls["collect"] += 1
        return {f"c{a}-{b}": self._items(a, b) for a, b in self.batches[batch_id]}

    def sprite(self, ctx, chapter, scene, used_subjects, base_palette, previous_error=None):
        self.calls["sprite"] += 1
        self.sprite_prompts.append({"chapter": chapter, "scene": scene, "used": list(used_subjects),
                                    "palette": base_palette, "error": previous_error})
        if self.sprite_outputs:
            out = self.sprite_outputs.pop(0)
            if isinstance(out, Exception):
                raise out
            return out
        # elige el primer elemento de la ficha que no se haya usado (como pide el prompt)
        options = [e["nombre"] for e in (scene or {}).get("elementos", [])]
        subject = next((o for o in options if o not in used_subjects), f"Libre {chapter}")
        return {**good_sprite(f"Heroe {chapter}"), "subject": subject}
