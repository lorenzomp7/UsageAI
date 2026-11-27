"""Icona della barra delle notifiche: un cerchio colorato con la % settimanale."""
from __future__ import annotations

from PIL import Image, ImageDraw, ImageFont

GREY = (120, 120, 120, 255)
GREEN = (46, 160, 67, 255)
ORANGE = (230, 140, 20, 255)
RED = (210, 50, 45, 255)


def color_for(percent: float | None) -> tuple:
    if percent is None:
        return GREY
    if percent >= 85:
        return RED
    if percent >= 60:
        return ORANGE
    return GREEN


def _font(size: int):
    for name in ("segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def render(percent: float | None, worst: float | None = None, size: int = 64) -> Image.Image:
    """percent = numero mostrato (settimana); worst = valore che decide il colore."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((1, 1, size - 2, size - 2), fill=color_for(worst if worst is not None else percent))
    text = "?" if percent is None else ("99" if percent >= 99.5 else f"{percent:.0f}")
    font = _font(int(size * (0.58 if len(text) < 2 else 0.5)))
    box = d.textbbox((0, 0), text, font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    d.text(((size - w) / 2 - box[0], (size - h) / 2 - box[1]), text, font=font, fill="white")
    return img
