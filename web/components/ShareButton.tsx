"use client";
import { useState } from "react";

/** Comparte la tarjeta PNG (sellada o revelada). Web Share con archivos; si no, descarga. */
export async function shareCard(bookId: number, sealed: boolean, title: string) {
  const res = await fetch(`/api/books/${bookId}/share-card.png?sealed=${sealed}`);
  if (!res.ok) throw new Error("No se pudo generar la tarjeta");
  const blob = await res.blob();
  const file = new File([blob], "pre-spoiler.png", { type: "image/png" });
  if (navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title });
      return;
    } catch (e) {
      if ((e as DOMException).name === "AbortError") return;
    }
  }
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "pre-spoiler.png";
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
}

export function ShareButton({
  bookId,
  sealed,
  title,
  className = "pixel-btn",
  label = "📤 Compartir",
}: {
  bookId: number;
  sealed: boolean;
  title: string;
  className?: string;
  label?: string;
}) {
  const [busy, setBusy] = useState(false);
  return (
    <button
      className={className}
      disabled={busy}
      onClick={async () => {
        setBusy(true);
        try {
          await shareCard(bookId, sealed, title);
        } catch {
          /* sin acción: el usuario puede reintentar */
        } finally {
          setBusy(false);
        }
      }}
    >
      {busy ? "…" : label}
    </button>
  );
}
