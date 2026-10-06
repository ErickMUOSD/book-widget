"use client";
import { useState } from "react";
import { api, send } from "@/lib/api";
import { useApi, useLocalSetting } from "@/lib/hooks";
import type { DeviceRow, Theme } from "@/lib/types";

export default function Settings() {
  const [anim, setAnim] = useLocalSetting("bw.anim", true);
  const [theme, setTheme] = useLocalSetting("bw.theme", "auto");
  const [overlay, setOverlay] = useLocalSetting("bw.overlay", false);
  const themes = useApi<Record<string, Theme>>("/themes");
  const devices = useApi<DeviceRow[]>("/devices");
  const config = useApi<{ backend_url: string; backend_ok: boolean }>("/config");
  const [name, setName] = useState("");
  const [created, setCreated] = useState<{ name: string; token: string } | null>(null);
  const [error, setError] = useState("");

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-xl font-semibold">Ajustes</h1>

      <section className="panel flex flex-col gap-4">
        <h2 className="font-semibold">Widget web</h2>
        <label className="flex items-center gap-3 text-sm">
          <input type="checkbox" checked={anim} onChange={(e) => setAnim(e.target.checked)} />
          Animación idle del personaje
        </label>
        <label className="text-sm flex flex-col gap-1 sm:w-72">
          Tema
          <select className="field" value={theme} onChange={(e) => setTheme(e.target.value)}>
            <option value="auto">Automático (según el género del libro)</option>
            {Object.values(themes.data ?? {}).map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
        </label>
        <label className="flex items-start gap-3 text-sm">
          <input type="checkbox" className="mt-1" checked={overlay} onChange={(e) => setOverlay(e.target.checked)} />
          <span>
            Widget flotante dentro del panel (arrastrable)
            <span className="block text-xs opacity-60">
              Alternativa si tu navegador no soporta «Anclar encima de todo». Estos ajustes se guardan en este navegador.
            </span>
          </span>
        </label>
        <a href="/widget" target="_blank" rel="noreferrer" className="btn-ghost w-fit">Abrir /widget en una ventana ↗</a>
      </section>

      <section className="panel flex flex-col gap-3">
        <h2 className="font-semibold">Backend</h2>
        <p className="text-sm">
          <code className="opacity-80">{config.data?.backend_url ?? "…"}</code>{" "}
          {config.data && (config.data.backend_ok ? <span className="text-emerald-400">● conectado</span> : <span className="text-red-400">● sin respuesta</span>)}
        </p>
        <p className="text-xs opacity-60">Se configura con BACKEND_URL y ADMIN_TOKEN en el servidor; el token nunca llega al navegador.</p>
      </section>

      <section className="panel flex flex-col gap-3">
        <h2 className="font-semibold">Dispositivos (app Android)</h2>
        <p className="text-xs opacity-60">Cada dispositivo usa su propio token. Revoca uno para cortarle el acceso.</p>
        <form
          className="flex gap-2 flex-wrap"
          onSubmit={async (e) => {
            e.preventDefault();
            setError("");
            try {
              const d = await api<{ name: string; token: string }>("/devices", send("POST", { name }));
              setCreated(d);
              setName("");
              devices.reload();
            } catch (er) {
              setError((er as Error).message);
            }
          }}
        >
          <input className="field sm:w-64" placeholder="Nombre (p. ej. Pixel 8)" value={name} onChange={(e) => setName(e.target.value)} required />
          <button className="btn">Crear token</button>
        </form>
        {error && <p className="text-sm text-red-400">{error}</p>}
        {created && (
          <div className="border border-amber-500/40 bg-amber-500/10 rounded-lg p-3 text-sm flex flex-col gap-2">
            <p>Token de «{created.name}» — cópialo ahora, no se vuelve a mostrar:</p>
            <code className="break-all select-all bg-black/40 p-2 rounded">{created.token}</code>
            <div className="flex gap-2">
              <button className="btn-ghost" onClick={() => navigator.clipboard?.writeText(created.token)}>Copiar</button>
              <button className="btn-ghost" onClick={() => setCreated(null)}>Listo</button>
            </div>
          </div>
        )}
        <ul className="divide-y divide-[var(--line)]">
          {devices.data?.map((d) => (
            <li key={d.id} className="py-2 flex items-center justify-between text-sm">
              <span>{d.name} <span className="opacity-50 text-xs">· {new Date(d.created_at).toLocaleDateString()}</span></span>
              <button
                className="btn-danger"
                onClick={async () => {
                  if (!confirm(`¿Revocar «${d.name}»?`)) return;
                  await api(`/devices/${d.id}`, send("DELETE"));
                  devices.reload();
                }}
              >
                Revocar
              </button>
            </li>
          ))}
          {devices.data?.length === 0 && <li className="py-2 text-sm opacity-60">Sin dispositivos.</li>}
        </ul>
      </section>
    </div>
  );
}
