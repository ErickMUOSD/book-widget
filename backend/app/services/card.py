"""Tarjeta PNG para compartir: sprite + leyenda (o sellada) con el tema del género."""
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from app.services.render import sprite_image
from app.services.sprite import SpriteData

W, H = 720, 960
FONT_PATH = Path(__file__).resolve().parent.parent / "assets" / "PressStart2P-Regular.ttf"


def _font(size: int) -> ImageFont.ImageFont:
    try:
        return ImageFont.truetype(str(FONT_PATH), size)
    except OSError:
        return ImageFont.load_default(size)


def _wrap(draw: ImageDraw.ImageDraw, text: str, font, max_w: int) -> list[str]:
    lines: list[str] = []
    line = ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if draw.textlength(trial, font=font) <= max_w:
            line = trial
        else:
            if line:
                lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def sealed_text(caption: str) -> str:
    """Texto 'cifrado' con la misma forma que la leyenda (para la tarjeta sellada)."""
    glyphs = "#%*+"
    out = []
    for i, word in enumerate(caption.split()):
        out.append("".join(glyphs[(i + j) % len(glyphs)] for j in range(max(2, min(len(word), 9)))))
    return " ".join(out)


def build_card(
    *, theme: dict, book_title: str, chapter_label: str, sprite: SpriteData | None,
    caption: str | None, sealed: bool,
) -> bytes:
    img = Image.new("RGB", (W, H), theme["bg"])
    d = ImageDraw.Draw(img)
    # marco pixel doble
    d.rectangle([12, 12, W - 13, H - 13], outline=theme["border"], width=8)
    d.rectangle([32, 32, W - 33, H - 33], outline=theme["card"], width=4)

    title_f, small_f, body_f = _font(26), _font(16), _font(18)
    y = 64
    for line in _wrap(d, book_title.upper(), title_f, W - 140)[:2]:
        w = d.textlength(line, font=title_f)
        d.text(((W - w) / 2, y), line, font=title_f, fill=theme["text"])
        y += 40
    label_w = d.textlength(chapter_label, font=small_f)
    d.text(((W - label_w) / 2, y + 8), chapter_label, font=small_f, fill=theme["accent"])

    if sprite is not None:
        art = sprite_image(sprite, 0, 12)  # 384×384
        d.rectangle([(W - 424) / 2, 190, (W + 424) / 2, 614], fill=theme["card"], outline=theme["border"], width=4)
        img.paste(art, ((W - art.width) // 2, 210), art)
        nf = _font(20)
        nw = d.textlength(sprite.name, font=nf)
        d.text(((W - nw) / 2, 636), sprite.name, font=nf, fill=theme["accent"])

    text = caption or ""
    if sealed:
        text = sealed_text(text) if text else "### %%% ***"
        tag = "[ SELLADO ]"
    else:
        tag = "[ PROXIMO CAPITULO ]" if caption else "[ FIN ]"
    tw = d.textlength(tag, font=small_f)
    d.text(((W - tw) / 2, 690), tag, font=small_f, fill=theme["accent"])
    y = 730
    for line in _wrap(d, text if text else "Fin del libro", body_f, W - 130)[:7]:
        d.text((65, y), line, font=body_f, fill=theme["text"])
        y += 30

    buf = BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()
