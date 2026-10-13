package com.bookwidget.app.data

/** Temas por género (portados de backend/app/services/themes.py). */
private val THEMES = listOf(
    Theme("terror", "Terror", "#10131c", "#1b2030", "#5b6b8c", "#d6dcec", "#b3262e",
        listOf("#10131c", "#2a3350", "#5b6b8c", "#8fa0c4", "#d6dcec", "#b3262e", "#6e1520", "#e8d9a0")),
    Theme("fantasia", "Fantasía", "#2b1a4a", "#3d2569", "#ffd166", "#fff4d6", "#06d6a0",
        listOf("#1d0f33", "#6a3fc2", "#a77bff", "#06d6a0", "#ffd166", "#ff6b9d", "#fff4d6", "#3b82f6")),
    Theme("ciencia_ficcion", "Ciencia ficción", "#06141f", "#0b2538", "#22d3ee", "#d8f6ff", "#f472b6",
        listOf("#06141f", "#0b2538", "#1e6f8f", "#22d3ee", "#d8f6ff", "#f472b6", "#a3e635", "#64748b")),
    Theme("romance", "Romance", "#3b1230", "#561a45", "#ff8fab", "#ffe5ec", "#ffd6a5",
        listOf("#3b1230", "#9d2c6b", "#ff4d8d", "#ff8fab", "#ffe5ec", "#ffd6a5", "#fb6f92", "#7a1f4d")),
    Theme("misterio", "Misterio / Noir", "#141414", "#222222", "#9a9a9a", "#ececec", "#e6b800",
        listOf("#0d0d0d", "#2b2b2b", "#555555", "#8c8c8c", "#cfcfcf", "#f5f5f5", "#e6b800", "#7a1f1f")),
    Theme("aventura", "Aventura", "#0f3b2e", "#17543f", "#f4a259", "#fff3dc", "#e63946",
        listOf("#0b2a21", "#17543f", "#2fa36b", "#9be564", "#f4a259", "#fff3dc", "#e63946", "#8b5a2b")),
    Theme("clasico", "Clásico", "#3a2a1a", "#4f3a24", "#d9b26f", "#f6ead2", "#a4402a",
        listOf("#2a1d10", "#6b4a2b", "#a97c50", "#d9b26f", "#f6ead2", "#a4402a", "#5c6b3a", "#2d3a4f")),
    Theme("infantil", "Infantil", "#1d4ed8", "#2563eb", "#fde047", "#ffffff", "#fb7185",
        listOf("#1e3a8a", "#38bdf8", "#fde047", "#fb7185", "#4ade80", "#ffffff", "#f97316", "#a78bfa")),
    Theme("historico", "Histórico", "#2d2a24", "#413c32", "#c0a062", "#efe6d2", "#8c2f39",
        listOf("#1f1c17", "#5a5242", "#8f8468", "#c0a062", "#efe6d2", "#8c2f39", "#3e5c76", "#b08968")),
    Theme("otro", "General", "#1f2937", "#2b3646", "#9ca3af", "#f3f4f6", "#facc15",
        listOf("#111827", "#374151", "#6b7280", "#9ca3af", "#f3f4f6", "#facc15", "#ef4444", "#3b82f6")),
).associateBy { it.id }

fun themeFor(genre: String): Theme = THEMES[genre] ?: THEMES.getValue("otro")

/** Género a partir de las materias y estanterías de Gutenberg: sin IA. */
private val GENRE_KEYWORDS = listOf(
    "terror" to listOf("horror", "vampire", "ghost", "supernatural", "gothic", "terror", "vampiro", "fantasmas"),
    "ciencia_ficcion" to listOf("science fiction", "ciencia ficción", "ciencia ficcion"),
    "fantasia" to listOf("fantasy", "fairy", "magic", "fantasía", "fantastic", "magic realism", "realismo mágico"),
    "misterio" to listOf("detective", "mystery", "crime", "murder", "policiaca", "policíaca", "misterio", "thriller"),
    "aventura" to listOf("adventure", "voyages", "sea stories", "pirates", "travel"),
    "romance" to listOf("love stories", "romance", "romántica", "amor"),
    "infantil" to listOf("juvenile", "children", "infantil", "juvenil"),
    "historico" to listOf("historical fiction", "history", "histórica", "historia"),
)

fun genreFromSubjects(subjects: List<String>): String {
    val s = subjects.joinToString(" | ").lowercase()
    return GENRE_KEYWORDS.firstOrNull { (_, words) -> words.any { it in s } }?.first ?: "clasico"
}
