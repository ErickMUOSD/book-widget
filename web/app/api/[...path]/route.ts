import { NextRequest, NextResponse } from "next/server";
import { backendFetch } from "@/lib/backend";
import { getSession } from "@/lib/session";

/** BFF: reenvía /api/* a FastAPI añadiendo el token maestro; exige sesión del panel. */
async function proxy(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const session = await getSession();
  if (!session.ok) return NextResponse.json({ error: "No autenticado" }, { status: 401 });

  const { path } = await ctx.params;
  if (path.some((p) => p === ".." || p === "." || p === "")) {
    return NextResponse.json({ error: "Ruta inválida" }, { status: 400 });
  }
  const hasBody = !["GET", "HEAD"].includes(req.method);
  const headers: Record<string, string> = {};
  const ct = req.headers.get("content-type");
  if (ct) headers["content-type"] = ct;

  let upstream: Response;
  try {
    upstream = await backendFetch(`/${path.map(encodeURIComponent).join("/")}${req.nextUrl.search}`, {
      method: req.method,
      headers,
      body: hasBody ? await req.arrayBuffer() : undefined,
    });
  } catch {
    return NextResponse.json({ detail: "No se pudo contactar con el backend" }, { status: 502 });
  }

  const out = new Headers();
  for (const h of ["content-type", "cache-control"]) {
    const v = upstream.headers.get(h);
    if (v) out.set(h, v);
  }
  return new Response(upstream.body, { status: upstream.status, headers: out });
}

export { proxy as GET, proxy as POST, proxy as PUT, proxy as PATCH, proxy as DELETE };
