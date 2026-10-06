"use client";
import { useEffect, useState } from "react";
import { imgUrl } from "@/lib/api";
import type { SpriteInfo } from "@/lib/types";

/** Sprite pixel art animado: el frame 0 es el base y los demás son variaciones idle (parpadeo, respiración…). */
export function PixelSprite({
  sprite,
  size = 192,
  animate = true,
}: {
  sprite: SpriteInfo | null;
  size?: number;
  animate?: boolean;
}) {
  const [shown, setShown] = useState(0);
  const urls = sprite?.frame_urls ?? [];

  useEffect(() => {
    setShown(0);
    if (!animate || urls.length < 2) return;
    // base, base, variación, base, base, variación…
    const seq: number[] = [];
    for (let f = 1; f < urls.length; f++) seq.push(0, 0, f);
    let k = 0;
    const t = setInterval(() => {
      k = (k + 1) % seq.length;
      setShown(seq[k]);
    }, 450);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sprite?.id, animate, urls.length]);

  if (!sprite) {
    return (
      <div
        style={{ width: size, height: size }}
        className="flex items-center justify-center font-pixel text-[10px] opacity-60"
      >
        ...
      </div>
    );
  }
  return (
    <div style={{ width: size, height: size, position: "relative" }} role="img" aria-label={sprite.name}>
      {/* todos los frames montados: se precargan y no hay parpadeo al cambiar */}
      {urls.map((u, i) => (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          key={u}
          src={imgUrl(u)}
          alt=""
          width={size}
          height={size}
          draggable={false}
          style={{
            position: "absolute",
            inset: 0,
            imageRendering: "pixelated",
            opacity: i === shown ? 1 : 0,
          }}
        />
      ))}
    </div>
  );
}
