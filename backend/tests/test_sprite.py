import pytest

from app.services.render import sprite_png
from app.services.sprite import SpriteError, fallback_sprite, frame_grid, validate_sprite
from tests.fakes import good_sprite


def test_valid_sprite_roundtrip_and_frames():
    s = validate_sprite(good_sprite())
    assert s.frame_count == 3
    base = frame_grid(s, 0)
    f1 = frame_grid(s, 1)
    assert f1[12][12] == 1 and f1[12][13] == 1
    # los diffs son independientes: el frame 2 no arrastra el 1
    f2 = frame_grid(s, 2)
    assert f2[8][10] == -1 and f2[12][12] == base[12][12]
    assert sprite_png(s, 2).startswith(b"\x89PNG")


@pytest.mark.parametrize("mutate,msg", [
    (lambda d: d.update(pixels=d["pixels"][:-1]), "32 filas"),
    (lambda d: d["pixels"].__setitem__(0, "x" * 32), "inválidos"),
    (lambda d: d["pixels"].__setitem__(0, "." * 31), "32 caracteres"),
    (lambda d: d.update(palette=["#000"]), "entre 2 y 16"),
    (lambda d: d.update(palette=["#101010", "rojo"]), "#RRGGBB"),
    (lambda d: d["pixels"].__setitem__(9, "." * 10 + "5" * 12 + "." * 10), "inválidos"),  # índice fuera de paleta
    (lambda d: d.update(frames=[[[40, 0, 1]]]), "coordenadas"),
    (lambda d: d.update(frames=[[[0, 0, 9]]]), "paleta"),
    (lambda d: d.update(frames=[[[0, 0]]]), "triples"),
    (lambda d: d.update(frames=[[], [], [], []]), "máximo"),
    (lambda d: d.update(pixels=["." * 32] * 32), "vacío"),
])
def test_invalid_sprites_rejected(mutate, msg):
    d = good_sprite()
    mutate(d)
    with pytest.raises(SpriteError, match=msg):
        validate_sprite(d)


def test_fallback_is_valid():
    f = fallback_sprite()
    validate_sprite({"name": f.name, "palette": f.palette, "pixels": f.rows, "frames": f.frames})
