import { NextResponse } from "next/server";
import { BACKEND_URL, backendFetch } from "@/lib/backend";
import { getSession } from "@/lib/session";

/** Información de solo lectura para la pantalla de Ajustes (nunca devuelve secretos). */
export async function GET() {
  if (!(await getSession()).ok) return NextResponse.json({ error: "No autenticado" }, { status: 401 });
  let backend_ok = false;
  try {
    backend_ok = (await backendFetch("/health")).ok;
  } catch {}
  return NextResponse.json({ backend_url: BACKEND_URL, backend_ok });
}
