"""Render con Pillow: PNG transparente por frame (nearest-neighbor)."""
from io import BytesIO

from PIL import Image

from app.services.sprite import SIZE, SpriteData, frame_grid


def _hex(c: str) -> tuple[int, int, int, int]:
    return int(c[1:3], 16), int(c[3:5], 16), int(c[5:7], 16), 255


def sprite_image(sprite: SpriteData, frame: int = 0, scale: int = 8) -> Image.Image:
    grid = frame_grid(sprite, frame)
    rgba = [_hex(c) for c in sprite.palette]
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(grid):
        for x, idx in enumerate(row):
            if idx >= 0:
                px[x, y] = rgba[idx]
    return img.resize((SIZE * scale, SIZE * scale), Image.NEAREST)


def sprite_png(sprite: SpriteData, frame: int = 0, scale: int = 8) -> bytes:
    buf = BytesIO()
    sprite_image(sprite, frame, scale).save(buf, format="PNG")
    return buf.getvalue()
