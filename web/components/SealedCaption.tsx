"use client";
import { scramble } from "@/lib/scramble";

export function SealedCaption({
  caption,
  sealed,
  finished,
  busy,
  onBreakSeal,
}: {
  caption: string | null;
  sealed: boolean;
  finished: boolean;
  busy: boolean;
  onBreakSeal: () => void;
}) {
  if (finished || !caption) {
    return (
      <p className="font-pixel text-[10px] leading-5 text-center opacity-80">
        {finished ? "FIN 📖 ¡Terminaste el libro!" : "Preparando la leyenda…"}
      </p>
    );
  }
  if (sealed) {
    return (
      <div className="flex flex-col items-center gap-2">
        <p aria-label="Leyenda sellada" className="font-pixel text-[10px] leading-5 text-center break-words opacity-70 select-none">
          {scramble(caption)}
        </p>
        <button
          onClick={onBreakSeal}
          disabled={busy}
          className="pixel-btn"
          style={{ background: "var(--w-accent)", color: "var(--w-bg)" }}
        >
          🔏 Romper sello
        </button>
      </div>
    );
  }
  return (
    <p key={caption} className="text-[15px] leading-snug text-center animate-[fadeIn_.6s_ease]">
      {caption}
    </p>
  );
}
