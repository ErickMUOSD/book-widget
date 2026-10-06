"""Validación del sprite generado por Claude, diffs de animación y sprite de reserva."""
import json
import re
from dataclasses import dataclass

SIZE = 32
MAX_COLORS = 16
MAX_FRAME_DIFFS = 3
MAX_CHANGES_PER_DIFF = 60
MIN_FILLED_PIXELS = 40
INDEX_CHARS = "0123456789abcdef"  # índice de paleta -> carácter
TRANSPARENT = "."
_HEX = re.compile(r"^#[0-9a-fA-F]{6}$")


class SpriteError(ValueError):
    pass


@dataclass
class SpriteData:
    name: str
    palette: list[str]
    rows: list[str]
    frames: list[list[list[int]]]  # diffs sobre el frame base: [[x, y, idx], ...]; idx -1 = transparente

    @property
    def frame_count(self) -> int:
        return 1 + len(self.frames)

    def to_json(self) -> tuple[str, str, str]:
        return json.dumps(self.palette), json.dumps(self.rows), json.dumps(self.frames)

    @classmethod
    def from_json(cls, name: str, palette: str, rows: str, frames: str) -> "SpriteData":
        return cls(name, json.loads(palette), json.loads(rows), json.loads(frames))


def validate_sprite(raw: dict) -> SpriteData:
    """Valida y normaliza la salida de Claude. Lanza SpriteError con un mensaje útil
    (se reenvía al modelo en el reintento)."""
    if not isinstance(raw, dict):
        raise SpriteError("la respuesta no es un objeto JSON")
    name = str(raw.get("name") or "").strip()[:40] or "Sin nombre"

    palette = raw.get("palette")
    if not isinstance(palette, list) or not (2 <= len(palette) <= MAX_COLORS):
        raise SpriteError(f"palette debe tener entre 2 y {MAX_COLORS} colores")
    if not all(isinstance(c, str) and _HEX.match(c) for c in palette):
        raise SpriteError("palette solo admite colores '#RRGGBB'")
    palette = [c.lower() for c in palette]

    rows = raw.get("pixels")
    if not isinstance(rows, list) or len(rows) != SIZE:
        raise SpriteError(f"pixels debe tener exactamente {SIZE} filas")
    allowed = TRANSPARENT + INDEX_CHARS[: len(palette)]
    filled = 0
    for i, row in enumerate(rows):
        if not isinstance(row, str) or len(row) != SIZE:
            raise SpriteError(f"la fila {i} debe ser un string de exactamente {SIZE} caracteres")
        bad = set(row) - set(allowed)
        if bad:
            raise SpriteError(
                f"la fila {i} usa caracteres inválidos {sorted(bad)}; solo se permite '{allowed}'"
            )
        filled += sum(1 for ch in row if ch != TRANSPARENT)
    if filled < MIN_FILLED_PIXELS:
        raise SpriteError("el dibujo está casi vacío; dibuja un personaje completo")

    frames = raw.get("frames") or []
    if not isinstance(frames, list) or len(frames) > MAX_FRAME_DIFFS:
        raise SpriteError(f"frames admite como máximo {MAX_FRAME_DIFFS} diffs")
    clean: list[list[list[int]]] = []
    for fi, diff in enumerate(frames):
        if not isinstance(diff, list) or len(diff) > MAX_CHANGES_PER_DIFF:
            raise SpriteError(f"el diff {fi} supera {MAX_CHANGES_PER_DIFF} cambios")
        out = []
        for ch in diff:
            if (
                not isinstance(ch, list)
                or len(ch) != 3
                or not all(isinstance(v, int) and not isinstance(v, bool) for v in ch)
            ):
                raise SpriteError(f"el diff {fi} debe contener triples enteros [x, y, color]")
            x, y, c = ch
            if not (0 <= x < SIZE and 0 <= y < SIZE):
                raise SpriteError(f"el diff {fi} tiene coordenadas fuera de 0..{SIZE - 1}")
            if not (-1 <= c < len(palette)):
                raise SpriteError(f"el diff {fi} usa un color fuera de la paleta")
            out.append([x, y, c])
        clean.append(out)
    return SpriteData(name=name, palette=palette, rows=list(rows), frames=clean)


def frame_grid(sprite: SpriteData, frame: int) -> list[list[int]]:
    """Matriz SIZE×SIZE de índices (-1 = transparente) del frame pedido.
    El frame 0 es el base; el frame i aplica el diff i-1 sobre el base."""
    if not (0 <= frame < sprite.frame_count):
        raise IndexError(frame)
    grid = [[-1 if ch == TRANSPARENT else INDEX_CHARS.index(ch) for ch in row] for row in sprite.rows]
    if frame > 0:
        for x, y, c in sprite.frames[frame - 1]:
            grid[y][x] = c
    return grid


def fallback_sprite(seed: str = "") -> SpriteData:
    """Librito con cara y dos frames (parpadeo / saltito) para cuando Claude falla."""
    palette = ["#1c1230", "#6c4bd1", "#9b8cff", "#fff4d6", "#ffd166", "#ff6b9d"]
    g = [["."] * SIZE for _ in range(SIZE)]

    def rect(x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                g[y][x] = INDEX_CHARS[c]

    rect(7, 6, 24, 27, 0)    # contorno
    rect(8, 7, 23, 26, 1)    # tapa
    rect(9, 8, 22, 25, 2)    # cubierta clara
    rect(8, 7, 8, 26, 0)     # lomo
    rect(11, 10, 20, 21, 3)  # "página"
    rect(13, 13, 14, 15, 0)  # ojos
    rect(17, 13, 18, 15, 0)
    rect(14, 18, 17, 18, 5)  # sonrisa
    rect(13, 17, 13, 17, 5)
    rect(18, 17, 18, 17, 5)
    rect(11, 28, 14, 29, 0)  # patitas
    rect(17, 28, 20, 29, 0)
    rect(12, 3, 19, 5, 4)    # estrellita/corona
    rect(15, 2, 16, 2, 4)
    rows = ["".join(r) for r in g]
    blink = [[13, 14, 3], [13, 15, 3], [14, 14, 3], [14, 15, 3], [17, 14, 3], [17, 15, 3], [18, 14, 3], [18, 15, 3]]
    sparkle = [[15, 2, -1], [16, 2, -1], [12, 3, -1], [19, 3, -1], [15, 1, 4], [16, 1, 4]]
    return SpriteData(name="Librito", palette=palette, rows=rows, frames=[blink, sparkle])
