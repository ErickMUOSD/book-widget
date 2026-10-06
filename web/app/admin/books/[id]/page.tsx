"use client";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { PixelSprite } from "@/components/PixelSprite";
import { ShareButton } from "@/components/ShareButton";
import { WidgetCard } from "@/components/WidgetCard";
import { api, send } from "@/lib/api";
import { useApi, useLocalSetting, useWidgetController } from "@/lib/hooks";
import {
  ELEMENT_TYPES, LEVELS,
  type BookDetail, type Level, type Scene, type SceneElement, type SpoilerRow, type SpriteInfo, type WidgetState,
} from "@/lib/types";

const words = (s: string) => s.trim().split(/\s+/).filter(Boolean).length;

// Elementos de la ficha como texto editable: una línea por elemento, "tipo: nombre — detalle".
const elementsToText = (els: SceneElement[]) =>
  els.map((e) => `${e.tipo}: ${e.nombre}${e.detalle ? ` — ${e.detalle}` : ""}`).join("\n");

function textToElements(text: string): SceneElement[] {
  return text.split("\n").map((line) => line.trim()).filter(Boolean).map((line) => {
    const m = line.match(/^([^:]+):\s*(.*)$/);
    const tipo = m && (ELEMENT_TYPES as readonly string[]).includes(m[1].trim().toLowerCase()) ? m[1].trim().toLowerCase() : "objeto";
    const rest = m && tipo === m[1].trim().toLowerCase() ? m[2] : line;
    const [nombre, ...detalle] = rest.split(/\s+[—-]\s+/);
    return { tipo, nombre: nombre.trim(), detalle: detalle.join(" — ").trim() };
  }).filter((e) => e.nombre);
}

function SceneEditor({ id, chapter, scene, onSaved }: { id: number; chapter: number; scene?: Scene; onSaved: () => void }) {
  const [err, setErr] = useState("");
  const [nudo, setNudo] = useState(scene?.nudo ?? "");
  const [desenlace, setDesenlace] = useState(scene?.desenlace ?? "");
  const [els, setEls] = useState(elementsToText(scene?.elementos ?? []));
  const dirty = nudo !== (scene?.nudo ?? "") || desenlace !== (scene?.desenlace ?? "") || els !== elementsToText(scene?.elementos ?? []);

  async function save() {
    if (!dirty) return;
    const elementos = textToElements(els);
    if (!elementos.length) return setErr("Añade al menos un elemento.");
    try {
      await api(`/books/${id}/scenes/${chapter}`, send("PUT", { nudo, desenlace, elementos }));
      setErr("");
      onSaved();
    } catch (e) {
      setErr((e as Error).message);
    }
  }
  return (
    <div className="grid gap-2 md:grid-cols-3 mt-2">
      <label className="text-xs flex flex-col gap-1">
        <span className="opacity-70">🎨 Nudo</span>
        <textarea className="field" rows={3} value={nudo} onChange={(e) => setNudo(e.target.value)} onBlur={save} />
      </label>
      <label className="text-xs flex flex-col gap-1">
        <span className="opacity-70">🎨 Desenlace (solo para el dibujo, no se muestra)</span>
        <textarea className="field" rows={3} value={desenlace} onChange={(e) => setDesenlace(e.target.value)} onBlur={save} />
      </label>
      <label className="text-xs flex flex-col gap-1">
        <span className="opacity-70">🎨 Elementos · «tipo: nombre — detalle»</span>
        <textarea className="field" rows={3} value={els} onChange={(e) => setEls(e.target.value)} onBlur={save}
          placeholder={"objeto: Nautilus — casco negro con remaches\nanimal: calamar gigante — tentáculos rojizos"} />
      </label>
      {err && <p className="text-xs text-red-400 md:col-span-3">{err}</p>}
    </div>
  );
}

function SpoilerEditor({ id }: { id: number }) {
  const rows = useApi<SpoilerRow[]>(`/books/${id}/spoilers`);
  const [err, setErr] = useState("");
  async function save(chapter: number, level: Level, caption: string, prev?: string) {
    if (caption === (prev ?? "") || !caption.trim()) return;
    try {
      await api(`/books/${id}/spoilers/${chapter}/${level}`, send("PUT", { caption }));
      setErr("");
      rows.reload();
    } catch (e) {
      setErr(`Cap. ${chapter} (${level}): ${(e as Error).message}`);
    }
  }
  return (
    <div className="flex flex-col gap-4">
      {err && <p className="text-sm text-red-400">{err}</p>}
      {rows.data?.map((r) => (
        <div key={r.chapter} className="border-t border-[var(--line)] pt-3">
          <p className="text-sm font-semibold mb-2">Capítulo {r.chapter}</p>
          <div className="grid gap-2 md:grid-cols-3">
            {LEVELS.map((l) => (
              <label key={l.id} className="text-xs flex flex-col gap-1">
                <span className="opacity-70">{l.icon} {l.label} · {words(r[l.id] ?? "")}/50</span>
                <textarea
                  className="field" rows={4} defaultValue={r[l.id] ?? ""}
                  key={`${r.chapter}-${l.id}-${r[l.id]}`}
                  onBlur={(e) => save(r.chapter, l.id, e.target.value, r[l.id])}
                  style={words(r[l.id] ?? "") > 50 ? { borderColor: "#f87171" } : undefined}
                />
              </label>
            ))}
          </div>
          <SceneEditor key={JSON.stringify(r.escena ?? null)} id={id} chapter={r.chapter} scene={r.escena} onSaved={rows.reload} />
        </div>
      ))}
    </div>
  );
}

export default function BookPage() {
  const id = Number(useParams<{ id: string }>().id);
  const router = useRouter();
  const [anim] = useLocalSetting("bw.anim", true);
  const [pending, setPending] = useState(true);
  const book = useApi<BookDetail>(`/books/${id}`, pending ? 4000 : 0);
  const sprites = useApi<SpriteInfo[]>(`/books/${id}/sprites`);
  const ctl = useWidgetController(id);
  const [chapter, setChapter] = useState(0);
  const [level, setLevel] = useState<Level>("pista");
  const [saving, setSaving] = useState("");
  const [error, setError] = useState("");
  const [showSpoilers, setShowSpoilers] = useState(false);
  const [total, setTotal] = useState("");
  const [synopsis, setSynopsis] = useState("");

  const b = book.data;
  useEffect(() => setPending(b?.status === "pending" || !b), [b?.status, b]);
  useEffect(() => {
    if (b) {
      setChapter(b.current_chapter);
      setLevel(b.spoiler_level);
    }
    // solo al cargar el libro por primera vez
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [b?.id]);

  async function act<T>(label: string, fn: () => Promise<T>) {
    setSaving(label);
    setError("");
    try {
      await fn();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving("");
    }
  }

  if (book.error && !b) return <p className="text-red-400">{book.error}</p>;
  if (!b) return <p className="opacity-60">Cargando…</p>;

  const ready = b.status === "ready";
  const titles = new Map(b.chapters.map((c) => [c.number, c.title]));
  const clampChapter = (n: number) => Math.max(0, Math.min(b.total_chapters, Number.isFinite(n) ? Math.trunc(n) : 0));
  const chapterLabel = (n: number, long = false) => {
    const t = titles.get(n);
    return long ? `capítulo ${n}${t ? `: «${t}»` : ""}` : `${n} — ${t || `Capítulo ${n}`}`;
  };
  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div>
          <Link href="/admin" className="text-sm opacity-60 hover:opacity-100">← Libros</Link>
          <h1 className="text-xl font-semibold mt-1">{b.title}</h1>
          <p className="text-sm opacity-60">
            {b.author || "—"} · {b.theme.name} · {b.total_chapters} capítulos
            {b.status === "pending" && ` · pre-spoilers ${b.spoilers_ready}/${b.spoilers_expected}`}
          </p>
        </div>
        <div className="flex gap-2">
          {!b.is_active && ready && (
            <button className="btn" onClick={() => act("activar", async () => { await api(`/books/${id}/activate`, send("POST")); book.reload(); })}>
              Usar en el widget
            </button>
          )}
          <button
            className="btn-danger"
            onClick={async () => {
              if (!confirm(`¿Eliminar «${b.title}» con todos sus spoilers y sprites?`)) return;
              await api(`/books/${id}`, send("DELETE"));
              router.replace("/admin");
            }}
          >
            Eliminar
          </button>
        </div>
      </div>

      {(b.status === "unknown" || b.status === "error") && (
        <div className="panel flex flex-col gap-3">
          <p className="text-sm">{b.status_detail}</p>
          <div className="grid sm:grid-cols-[180px_1fr] gap-3">
            <input className="field" type="number" min={1} max={500} placeholder="Nº de capítulos" value={total} onChange={(e) => setTotal(e.target.value)} />
            <input className="field" placeholder="Sinopsis breve (opcional)" value={synopsis} onChange={(e) => setSynopsis(e.target.value)} />
          </div>
          <div>
            <button
              className="btn"
              disabled={!!saving}
              onClick={() => act("retry", async () => {
                await api(`/books/${id}/retry`, send("POST", { total_chapters: total ? Number(total) : null, synopsis: synopsis || null }));
                book.reload();
              })}
            >
              Reintentar
            </button>
          </div>
        </div>
      )}

      {b.status === "pending" && <p className="panel text-sm">⏳ Preparando el libro… esta página se actualiza sola.</p>}

      {ready && (
        <div className="grid gap-6 lg:grid-cols-[320px_1fr]">
          <div className="flex justify-center lg:justify-start">
            <WidgetCard ctl={ctl} animate={anim} />
          </div>
          <div className="panel flex flex-col gap-4">
            <h2 className="font-semibold">¿En qué capítulo vas?</h2>
            <div className="grid sm:grid-cols-2 gap-3">
              <div className="text-sm flex flex-col gap-1">
                <label htmlFor="chapter-num">Terminé el capítulo</label>
                <div className="flex gap-2">
                  <input id="chapter-num" className="field w-20" type="number" min={0} max={b.total_chapters} value={chapter}
                    onChange={(e) => setChapter(clampChapter(Number(e.target.value)))} />
                  <select className="field flex-1 min-w-0" aria-label="Elegir capítulo terminado" value={chapter}
                    onChange={(e) => setChapter(Number(e.target.value))}>
                    <option value={0}>0 — Aún no empiezo</option>
                    {b.chapters.map((c) => <option key={c.number} value={c.number}>{chapterLabel(c.number)}</option>)}
                  </select>
                </div>
              </div>
              <label className="text-sm flex flex-col gap-1">
                Nivel de spoiler
                <select className="field" value={level} onChange={(e) => setLevel(e.target.value as Level)}>
                  {LEVELS.map((l) => <option key={l.id} value={l.id}>{l.icon} {l.label} — {l.hint}</option>)}
                </select>
              </label>
            </div>
            <p className="text-sm opacity-80">
              {chapter >= b.total_chapters
                ? "🎉 ¡Libro terminado! Se generará un dibujo de celebración."
                : <>Se generará para el {chapterLabel(chapter + 1, true)}.</>}
            </p>
            <div className="flex gap-2 flex-wrap">
              <button className="btn" disabled={!!saving}
                onClick={() => act("save", async () => {
                  const w = await api<WidgetState>(`/books/${id}/progress`, send("PUT", { current_chapter: chapter, spoiler_level: level }));
                  ctl.setState(w); book.reload(); sprites.reload();
                })}>
                {chapter >= b.total_chapters ? "Generar celebración" : `Generar para el capítulo ${chapter + 1}`}
              </button>
              <button className="btn-ghost" disabled={!!saving}
                onClick={() => act("regen", async () => {
                  ctl.setState(await api<WidgetState>(`/books/${id}/sprite/regenerate`, send("POST")));
                  sprites.reload();
                })}>
                🎲 Nuevo personaje
              </button>
              <ShareButton bookId={id} sealed={true} title={b.title} className="btn-ghost" label="📤 Compartir sellada" />
              <ShareButton bookId={id} sealed={false} title={b.title} className="btn-ghost" label="📤 Compartir revelada" />
            </div>
            {saving && <p className="text-sm opacity-70">Trabajando… generar el pixel art puede tardar unos segundos.</p>}
            {error && <p className="text-sm text-red-400">{error}</p>}
          </div>
        </div>
      )}

      {ready && (
        <section className="panel flex flex-col gap-3">
          <button className="text-left font-semibold" onClick={() => setShowSpoilers(!showSpoilers)}>
            {showSpoilers ? "▾" : "▸"} Pre-spoilers y fichas visuales por capítulo ({b.spoilers_ready})
          </button>
          {showSpoilers && <SpoilerEditor id={id} />}
        </section>
      )}

      {ready && (
        <section className="panel flex flex-col gap-3">
          <h2 className="font-semibold">Historial de personajes</h2>
          {!sprites.data?.length && <p className="text-sm opacity-60">Todavía no hay personajes.</p>}
          <div className="grid grid-cols-2 sm:grid-cols-4 md:grid-cols-6 gap-3">
            {sprites.data?.map((s) => (
              <div key={s.id} className="flex flex-col items-center gap-1 text-center text-xs">
                <div className="bg-[#0f1117] border border-[var(--line)] p-1"><PixelSprite sprite={s} size={96} animate={anim} /></div>
                <span className="font-semibold">{s.name}</span>
                {s.subject && <span className="opacity-80">🎨 {s.subject}</span>}
                <span className="opacity-60">
                  {s.fallback ? `tras cap. ${s.chapter} · reserva` : s.chapter < b.total_chapters ? `para cap. ${s.chapter + 1}` : "final"}
                </span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}
