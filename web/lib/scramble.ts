const GLYPHS = "#%*+";

/** Texto "rúnico" con la misma forma que la leyenda (mismo algoritmo que la tarjeta del backend). */
export function scramble(caption: string): string {
  return caption
    .split(/\s+/)
    .filter(Boolean)
    .map((word, i) =>
      Array.from({ length: Math.max(2, Math.min(word.length, 9)) }, (_, j) => GLYPHS[(i + j) % GLYPHS.length]).join(""),
    )
    .join(" ");
}
