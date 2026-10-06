import { timingSafeEqual } from "node:crypto";
import { NextResponse } from "next/server";
import { getSession } from "@/lib/session";

function same(a: string, b: string) {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

export async function POST(req: Request) {
  const { password } = (await req.json().catch(() => ({}))) as { password?: string };
  const expected = process.env.ADMIN_PASSWORD ?? "";
  if (!expected || typeof password !== "string" || !same(password, expected)) {
    return NextResponse.json({ error: "Contraseña incorrecta" }, { status: 401 });
  }
  const session = await getSession();
  session.ok = true;
  await session.save();
  return NextResponse.json({ ok: true });
}
