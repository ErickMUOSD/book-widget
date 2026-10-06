"""Temas por género: colores del widget + paleta base para el prompt del sprite."""

GENRES = [
    "terror",
    "fantasia",
    "ciencia_ficcion",
    "romance",
    "misterio",
    "aventura",
    "clasico",
    "infantil",
    "historico",
    "otro",
]

THEMES: dict[str, dict] = {
    "terror": {
        "name": "Terror",
        "bg": "#10131c", "card": "#1b2030", "border": "#5b6b8c", "text": "#d6dcec", "accent": "#b3262e",
        "base_palette": ["#10131c", "#2a3350", "#5b6b8c", "#8fa0c4", "#d6dcec", "#b3262e", "#6e1520", "#e8d9a0"],
    },
    "fantasia": {
        "name": "Fantasía",
        "bg": "#2b1a4a", "card": "#3d2569", "border": "#ffd166", "text": "#fff4d6", "accent": "#06d6a0",
        "base_palette": ["#1d0f33", "#6a3fc2", "#a77bff", "#06d6a0", "#ffd166", "#ff6b9d", "#fff4d6", "#3b82f6"],
    },
    "ciencia_ficcion": {
        "name": "Ciencia ficción",
        "bg": "#06141f", "card": "#0b2538", "border": "#22d3ee", "text": "#d8f6ff", "accent": "#f472b6",
        "base_palette": ["#06141f", "#0b2538", "#1e6f8f", "#22d3ee", "#d8f6ff", "#f472b6", "#a3e635", "#64748b"],
    },
    "romance": {
        "name": "Romance",
        "bg": "#3b1230", "card": "#561a45", "border": "#ff8fab", "text": "#ffe5ec", "accent": "#ffd6a5",
        "base_palette": ["#3b1230", "#9d2c6b", "#ff4d8d", "#ff8fab", "#ffe5ec", "#ffd6a5", "#fb6f92", "#7a1f4d"],
    },
    "misterio": {
        "name": "Misterio / Noir",
        "bg": "#141414", "card": "#222222", "border": "#9a9a9a", "text": "#ececec", "accent": "#e6b800",
        "base_palette": ["#0d0d0d", "#2b2b2b", "#555555", "#8c8c8c", "#cfcfcf", "#f5f5f5", "#e6b800", "#7a1f1f"],
    },
    "aventura": {
        "name": "Aventura",
        "bg": "#0f3b2e", "card": "#17543f", "border": "#f4a259", "text": "#fff3dc", "accent": "#e63946",
        "base_palette": ["#0b2a21", "#17543f", "#2fa36b", "#9be564", "#f4a259", "#fff3dc", "#e63946", "#8b5a2b"],
    },
    "clasico": {
        "name": "Clásico",
        "bg": "#3a2a1a", "card": "#4f3a24", "border": "#d9b26f", "text": "#f6ead2", "accent": "#a4402a",
        "base_palette": ["#2a1d10", "#6b4a2b", "#a97c50", "#d9b26f", "#f6ead2", "#a4402a", "#5c6b3a", "#2d3a4f"],
    },
    "infantil": {
        "name": "Infantil",
        "bg": "#1d4ed8", "card": "#2563eb", "border": "#fde047", "text": "#ffffff", "accent": "#fb7185",
        "base_palette": ["#1e3a8a", "#38bdf8", "#fde047", "#fb7185", "#4ade80", "#ffffff", "#f97316", "#a78bfa"],
    },
    "historico": {
        "name": "Histórico",
        "bg": "#2d2a24", "card": "#413c32", "border": "#c0a062", "text": "#efe6d2", "accent": "#8c2f39",
        "base_palette": ["#1f1c17", "#5a5242", "#8f8468", "#c0a062", "#efe6d2", "#8c2f39", "#3e5c76", "#b08968"],
    },
    "otro": {
        "name": "General",
        "bg": "#1f2937", "card": "#2b3646", "border": "#9ca3af", "text": "#f3f4f6", "accent": "#facc15",
        "base_palette": ["#111827", "#374151", "#6b7280", "#9ca3af", "#f3f4f6", "#facc15", "#ef4444", "#3b82f6"],
    },
}


def normalize_genre(genre: str | None) -> str:
    g = (genre or "").strip().lower().replace(" ", "_").replace("-", "_")
    aliases = {
        "fantasía": "fantasia", "ciencia-ficcion": "ciencia_ficcion", "scifi": "ciencia_ficcion",
        "sci_fi": "ciencia_ficcion", "noir": "misterio", "policiaco": "misterio", "suspenso": "misterio",
        "thriller": "misterio", "clásico": "clasico", "histórico": "historico", "histórica": "historico",
    }
    g = aliases.get(g, g)
    return g if g in THEMES else "otro"


def theme_for(genre: str) -> dict:
    return {"id": normalize_genre(genre), **THEMES[normalize_genre(genre)]}
