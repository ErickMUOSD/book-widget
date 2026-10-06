"use client";
import type { CSSProperties } from "react";
import { useApi } from "@/lib/hooks";
import type { WidgetController } from "@/lib/hooks";
import { LEVELS, type Theme } from "@/lib/types";
import { PixelSprite } from "./PixelSprite";
import { SealedCaption } from "./SealedCaption";
import { ShareButton } from "./ShareButton";

export function WidgetCard({
  ctl,
  animate = true,
  themeOverride = "auto",
  spriteSize = 176,
  footer,
}: {
  ctl: WidgetController;
  animate?: boolean;
  themeOverride?: string;
  spriteSize?: number;
  footer?: React.ReactNode;
}) {
  const { state, error, busy } = ctl;
  const themes = useApi<Record<string, Theme>>(themeOverride !== "auto" ? "/themes" : null);
  const theme: Theme | undefined = (themeOverride !== "auto" && themes.data?.[themeOverride]) || state?.theme;
  const vars = {
    "--w-bg": theme?.bg ?? "#1f2937",
    "--w-card": theme?.card ?? "#2b3646",
    "--w-border": theme?.border ?? "#9ca3af",
    "--w-text": theme?.text ?? "#f3f4f6",
    "--w-accent": theme?.accent ?? "#facc15",
    background: "var(--w-bg)",
    color: "var(--w-text)",
  } as CSSProperties;

  let body: React.ReactNode;
  if (!state) {
    body = (
      <p className="font-pixel text-[10px] leading-5 text-center py-10 opacity-80">
        {error ? `⚠ ${error}` : "Cargando…"}
        {error?.includes("libro activo") && <><br /><br />Añade un libro en el panel.</>}
      </p>
    );
  } else if (state.status !== "ready") {
    body = (
      <p className="font-pixel text-[10px] leading-5 text-center py-10">
        {state.title}
        <br /><br />
        {state.status === "pending" ? "⏳ Preparando el libro…" : `⚠ ${state.status_detail || "Requiere atención en el panel"}`}
      </p>
    );
  } else {
    body = (
      <>
        <header className="text-center">
          <h1 className="font-pixel text-[11px] leading-4 uppercase break-words">{state.title}</h1>
          <p className="font-pixel text-[9px] mt-1" style={{ color: "var(--w-accent)" }}>
            Cap. {state.current_chapter}/{state.total_chapters}
          </p>
        </header>
        <div className="flex flex-col items-center gap-1">
          <div style={{ background: "var(--w-card)", boxShadow: "inset 0 0 0 3px var(--w-border)" }} className="p-3">
            <PixelSprite sprite={state.sprite} size={spriteSize} animate={animate} />
          </div>
          <p className="font-pixel text-[9px]" style={{ color: "var(--w-accent)" }}>
            {state.sprite?.name ?? ""}
          </p>
        </div>
        <SealedCaption
          caption={state.caption}
          sealed={state.sealed}
          finished={state.finished}
          busy={busy === "seal"}
          onBreakSeal={() => ctl.setSealed(false)}
        />
        <div className="flex items-center justify-center gap-1" role="group" aria-label="Nivel de spoiler">
          {LEVELS.map((l) => (
            <button
              key={l.id}
              title={`${l.label}: ${l.hint}`}
              onClick={() => ctl.setLevel(l.id)}
              disabled={busy === "level"}
              className="pixel-btn !px-2"
              style={state.spoiler_level === l.id ? { background: "var(--w-border)", color: "var(--w-bg)" } : undefined}
            >
              {l.icon}
            </button>
          ))}
        </div>
        <div className="flex items-center justify-center gap-2 flex-wrap">
          <button
            className="pixel-btn"
            disabled={busy === "advance" || state.finished}
            onClick={ctl.advance}
            style={{ background: "var(--w-accent)", color: "var(--w-bg)" }}
          >
            {busy === "advance" ? "invocando…" : "Terminé ✓"}
          </button>
          <ShareButton bookId={state.book_id} sealed={state.sealed} title={state.title} label="📤" />
        </div>
        {error && <p className="font-pixel text-[8px] text-center opacity-80">⚠ {error}</p>}
      </>
    );
  }

  return (
    <div
      style={{ ...vars, boxShadow: "0 0 0 4px var(--w-border), 0 0 0 8px var(--w-card)" }}
      className="w-[280px] max-w-full p-4 flex flex-col gap-3 widget-root"
    >
      {body}
      {footer}
    </div>
  );
}
