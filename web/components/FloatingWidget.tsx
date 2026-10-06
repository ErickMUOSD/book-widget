"use client";
import { useEffect, useRef, useState } from "react";
import { useLocalSetting, useWidgetController } from "@/lib/hooks";
import { WidgetCard } from "./WidgetCard";

/** Fallback sin Picture-in-Picture: widget arrastrable que flota sobre cualquier página del panel. */
export function FloatingWidget() {
  const [enabled] = useLocalSetting("bw.overlay", false);
  const [animate] = useLocalSetting("bw.anim", true);
  const [themeOverride] = useLocalSetting("bw.theme", "auto");
  const [pos, setPos] = useLocalSetting("bw.overlayPos", { x: 16, y: 16 });
  const [collapsed, setCollapsed] = useState(false);
  const drag = useRef<{ dx: number; dy: number } | null>(null);
  const ctl = useWidgetController();

  useEffect(() => {
    const move = (e: PointerEvent) => {
      if (!drag.current) return;
      setPos({
        x: Math.max(0, Math.min(window.innerWidth - 60, e.clientX - drag.current.dx)),
        y: Math.max(0, Math.min(window.innerHeight - 40, e.clientY - drag.current.dy)),
      });
    };
    const up = () => (drag.current = null);
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
  }, [setPos]);

  if (!enabled) return null;
  return (
    <div style={{ position: "fixed", left: pos.x, top: pos.y, zIndex: 50 }}>
      <div
        onPointerDown={(e) => (drag.current = { dx: e.clientX - pos.x, dy: e.clientY - pos.y })}
        className="font-pixel text-[9px] bg-black/80 text-white px-2 py-1 cursor-grab select-none flex justify-between"
      >
        <span>⠿ widget</span>
        <button onClick={() => setCollapsed(!collapsed)} aria-label="Plegar">{collapsed ? "▢" : "–"}</button>
      </div>
      {!collapsed && <WidgetCard ctl={ctl} animate={animate} themeOverride={themeOverride} spriteSize={128} />}
    </div>
  );
}
