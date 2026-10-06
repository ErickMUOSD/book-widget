"use client";
import { useCallback, useEffect, useState } from "react";

interface DocPiP {
  requestWindow(opts?: { width?: number; height?: number }): Promise<Window>;
}
const api = () => (window as unknown as { documentPictureInPicture?: DocPiP }).documentPictureInPicture;

/** Document Picture-in-Picture (Chrome/Edge ≥116, solo en HTTPS o localhost):
 *  ventana pequeña que se queda siempre encima de las demás apps. */
export function usePip() {
  const [supported, setSupported] = useState(false);
  const [win, setWin] = useState<Window | null>(null);

  useEffect(() => setSupported(!!api()), []);

  const open = useCallback(async (width = 340, height = 520) => {
    const pip = await api()!.requestWindow({ width, height });
    // los estilos de la página no se heredan: se copian a la ventana flotante
    for (const sheet of Array.from(document.styleSheets)) {
      try {
        const style = document.createElement("style");
        style.textContent = Array.from(sheet.cssRules).map((r) => r.cssText).join("\n");
        pip.document.head.appendChild(style);
      } catch {
        if (sheet.href) {
          const link = document.createElement("link");
          link.rel = "stylesheet";
          link.href = sheet.href;
          pip.document.head.appendChild(link);
        }
      }
    }
    const base = pip.document.createElement("base");
    base.href = `${window.location.origin}/`;
    pip.document.head.prepend(base);
    pip.document.title = "Book Widget";
    pip.document.documentElement.className = document.documentElement.className;
    pip.document.body.className = "pip-body";
    pip.addEventListener("pagehide", () => setWin(null));
    setWin(pip);
  }, []);

  const close = useCallback(() => {
    win?.close();
    setWin(null);
  }, [win]);

  return { supported, win, open, close };
}
