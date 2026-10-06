import "server-only";

export const BACKEND_URL = (process.env.BACKEND_URL ?? "http://localhost:8000").replace(/\/$/, "");

/** Llamada servidor → FastAPI con el token maestro. El navegador nunca lo ve. */
export function backendFetch(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  headers.set("X-Device-Token", process.env.ADMIN_TOKEN ?? "");
  return fetch(`${BACKEND_URL}${path}`, { ...init, headers, cache: "no-store" });
}
