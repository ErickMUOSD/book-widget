"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, send } from "./api";
import type { Level, WidgetState } from "./types";

/** Valor persistido en localStorage (seguro ante SSR / modo privado). */
export function useLocalSetting<T>(key: string, initial: T): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(initial);
  useEffect(() => {
    try {
      const raw = localStorage.getItem(key);
      if (raw !== null) setValue(JSON.parse(raw) as T);
    } catch {}
  }, [key]);
  const set = useCallback(
    (v: T) => {
      setValue(v);
      try {
        localStorage.setItem(key, JSON.stringify(v));
      } catch {}
    },
    [key],
  );
  return [value, set];
}

/** Carga con polling opcional. */
export function useApi<T>(path: string | null, intervalMs = 0) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const alive = useRef(true);

  const load = useCallback(async () => {
    if (!path) return;
    try {
      const d = await api<T>(path);
      if (alive.current) {
        setData(d);
        setError(null);
      }
    } catch (e) {
      if (alive.current) setError((e as Error).message);
    } finally {
      if (alive.current) setLoading(false);
    }
  }, [path]);

  useEffect(() => {
    alive.current = true;
    load();
    if (!intervalMs) return () => void (alive.current = false);
    const t = setInterval(load, intervalMs);
    return () => {
      alive.current = false;
      clearInterval(t);
    };
  }, [load, intervalMs]);

  return { data, error, loading, reload: load, setData };
}

/** Estado del widget + acciones. bookId undefined → libro activo. Sincroniza cada 60 s y al enfocar. */
export function useWidgetController(bookId?: number) {
  const base = bookId ? `/books/${bookId}` : "";
  const [state, setState] = useState<WidgetState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<null | "advance" | "level" | "seal">(null);
  const stateRef = useRef<WidgetState | null>(null);
  stateRef.current = state;

  const refresh = useCallback(async () => {
    try {
      setState(await api<WidgetState>(bookId ? `${base}/widget` : "/widget"));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [base, bookId]);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 60_000);
    const onVis = () => document.visibilityState === "visible" && refresh();
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", onVis);
    return () => {
      clearInterval(t);
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", onVis);
    };
  }, [refresh]);

  const run = useCallback(async (kind: "advance" | "level" | "seal", fn: () => Promise<WidgetState>) => {
    setBusy(kind);
    try {
      setState(await fn());
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }, []);

  const id = () => stateRef.current!.book_id;
  return {
    state,
    error,
    busy,
    refresh,
    setState,
    advance: () => run("advance", () => api<WidgetState>(`/books/${id()}/advance`, send("POST"))),
    setSealed: (sealed: boolean) =>
      run("seal", () => api<WidgetState>(`/books/${id()}/state`, send("PATCH", { sealed }))),
    setLevel: (spoiler_level: Level) =>
      run("level", () => api<WidgetState>(`/books/${id()}/state`, send("PATCH", { spoiler_level }))),
  };
}

export type WidgetController = ReturnType<typeof useWidgetController>;
