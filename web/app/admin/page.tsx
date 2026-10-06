"use client";
import Link from "next/link";
import { useState } from "react";
import { api, send } from "@/lib/api";
import { useApi } from "@/lib/hooks";
import type { BookStatus, BookSummary } from "@/lib/types";

const STATUS: Record<BookStatus, { label: string; cls: string }> = {
  pending: { label: "⏳ Preparando", cls: "bg-amber-500/15 text-amber-300" },
  ready: { label: "✓ Listo", cls: "bg-emerald-500/15 text-emerald-300" },
  unknown: { label: "? Necesita datos", cls: "bg-sky-500/15 text-sky-300" },
  error: { label: "⚠ Error", cls: "bg-red-500/15 text-red-300" },
};

function AddBook({ onAdded }: { onAdded: () => void }) {
  const [title, setTitle] = useState("");
  const [author, setAuthor] = useState("");
  const [synopsis, setSynopsis] = useState("");
  const [total, setTotal] = useState("");
  const [extra, setExtra] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await api("/books", send("POST", { title, author, synopsis, total_chapters: total ? Number(total) : null }));
      setTitle(""); setAuthor(""); setSynopsis(""); setTotal("");
      onAdded();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="panel flex flex-col gap-3">
      <h2 className="font-semibold">Añadir un libro</h2>
      <div className="grid sm:grid-cols-2 gap-3">
        <input className="field" placeholder="Título" value={title} onChange={(e) => setTitle(e.target.value)} required />
        <input className="field" placeholder="Autor (opcional)" value={author} onChange={(e) => setAuthor(e.target.value)} />
      </div>
      <button type="button" className="text-left text-sm opacity-70 hover:opacity-100" onClick={() => setExtra(!extra)}>
        {extra ? "▾" : "▸"} Datos extra (para libros poco conocidos)
      </button>
      {extra && (
        <div className="grid gap-3">
          <input
            className="field sm:w-60" type="number" min={1} max={500} placeholder="Número de capítulos"
            value={total} onChange={(e) => setTotal(e.target.value)}
          />
          <textarea
            className="field" rows={3} maxLength={2000} placeholder="Sinopsis breve (evita que Claude invente)"
            value={synopsis} onChange={(e) => setSynopsis(e.target.value)}
          />
        </div>
      )}
      {error && <p className="text-sm text-red-400">{error}</p>}
      <div className="flex items-center gap-3">
        <button className="btn" disabled={busy || !title.trim()}>{busy ? "Enviando…" : "Dar de alta"}</button>
        <p className="text-xs opacity-60">Se pregeneran los pre-spoilers de todos los capítulos una sola vez.</p>
      </div>
    </form>
  );
}

export default function AdminHome() {
  const books = useApi<BookSummary[]>("/books", 8000);

  return (
    <div className="flex flex-col gap-6">
      <AddBook onAdded={books.reload} />
      <section className="flex flex-col gap-3">
        <h2 className="font-semibold">Mis libros</h2>
        {books.error && <p className="text-sm text-red-400">{books.error}</p>}
        {books.data?.length === 0 && <p className="text-sm opacity-60">Aún no hay libros.</p>}
        <div className="grid gap-3 sm:grid-cols-2">
          {books.data?.map((b) => (
            <div key={b.id} className="panel flex flex-col gap-2" style={{ borderLeft: `4px solid ${b.theme.border}` }}>
              <div className="flex items-start justify-between gap-2">
                <div>
                  <Link href={`/admin/books/${b.id}`} className="font-semibold hover:underline">{b.title}</Link>
                  <p className="text-xs opacity-60">{b.author || "—"} · {b.theme.name}</p>
                </div>
                {b.is_active && <span className="text-xs bg-[#6d5ae6] rounded px-2 py-0.5">activo</span>}
              </div>
              <div className="flex items-center gap-2 text-xs">
                <span className={`rounded px-2 py-0.5 ${STATUS[b.status].cls}`}>{STATUS[b.status].label}</span>
                {b.status === "ready" && <span className="opacity-70">Cap. {b.current_chapter}/{b.total_chapters}</span>}
              </div>
              {b.status_detail && <p className="text-xs opacity-70">{b.status_detail}</p>}
              <div className="flex gap-2 mt-1">
                <Link href={`/admin/books/${b.id}`} className="btn-ghost">Abrir</Link>
                {!b.is_active && b.status === "ready" && (
                  <button className="btn-ghost" onClick={async () => { await api(`/books/${b.id}/activate`, send("POST")); books.reload(); }}>
                    Usar en el widget
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
