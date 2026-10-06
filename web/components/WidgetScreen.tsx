"use client";
import { createPortal } from "react-dom";
import { useLocalSetting, useWidgetController } from "@/lib/hooks";
import { usePip } from "@/lib/pip";
import { WidgetCard } from "./WidgetCard";

/** Página /widget: ventana mínima (PWA) y botón para anclarlo encima de todas las apps. */
export function WidgetScreen() {
  const ctl = useWidgetController();
  const [animate] = useLocalSetting("bw.anim", true);
  const [themeOverride] = useLocalSetting("bw.theme", "auto");
  const pip = usePip();
  const card = <WidgetCard ctl={ctl} animate={animate} themeOverride={themeOverride} />;

  return (
    <main className="min-h-dvh flex flex-col items-center justify-center gap-4 p-4">
      {pip.win ? (
        <>
          {createPortal(
            <div className="min-h-dvh flex items-center justify-center p-3">{card}</div>,
            pip.win.document.body,
          )}
          <p className="font-pixel text-[10px] leading-5 text-center">
            📌 Anclado sobre tus ventanas.
          </p>
          <button className="pixel-btn" onClick={pip.close}>Traer aquí</button>
        </>
      ) : (
        <>
          {card}
          {pip.supported ? (
            <button className="pixel-btn" onClick={() => pip.open()}>📌 Anclar encima de todo</button>
          ) : (
            <p className="max-w-[280px] text-center text-xs opacity-70">
              Este navegador no permite una ventana flotante (hace falta Chrome/Edge sobre HTTPS o localhost).
              Instala esta página como app desde el menú del navegador, o activa el widget flotante dentro del
              panel en Ajustes.
            </p>
          )}
        </>
      )}
    </main>
  );
}
