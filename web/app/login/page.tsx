"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";

export default function Login() {
  const router = useRouter();
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const res = await fetch("/api/login", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ password }),
    });
    if (res.ok) router.replace("/admin");
    else {
      setError("Contraseña incorrecta");
      setBusy(false);
    }
  }

  return (
    <main className="min-h-dvh flex items-center justify-center p-4">
      <form onSubmit={submit} className="panel w-full max-w-sm flex flex-col gap-4">
        <h1 className="font-pixel text-sm leading-6">📖 Book Widget</h1>
        <label className="text-sm flex flex-col gap-1">
          Contraseña del panel
          <input
            type="password"
            className="field"
            autoFocus
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error && <p className="text-sm text-red-400">{error}</p>}
        <button className="btn" disabled={busy || !password}>Entrar</button>
      </form>
    </main>
  );
}
