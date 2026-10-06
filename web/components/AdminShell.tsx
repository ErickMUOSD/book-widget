"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { FloatingWidget } from "./FloatingWidget";

export function AdminShell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const router = useRouter();
  const link = (href: string, label: string, exact = false) => {
    const active = exact ? path === href : path.startsWith(href);
    return (
      <Link
        href={href}
        className={`px-3 py-1.5 rounded-lg text-sm ${active ? "bg-[#6d5ae6] text-white" : "hover:bg-[#222633]"}`}
      >
        {label}
      </Link>
    );
  };
  return (
    <div className="min-h-dvh">
      <nav className="border-b border-[var(--line)] px-4 py-3 flex items-center gap-2 flex-wrap">
        <span className="font-pixel text-[11px] mr-3">📖 BOOK WIDGET</span>
        {link("/admin", "Libros", true)}
        {link("/admin/settings", "Ajustes")}
        <a href="/widget" target="_blank" rel="noreferrer" className="px-3 py-1.5 rounded-lg text-sm hover:bg-[#222633]">
          Abrir widget ↗
        </a>
        <button
          className="ml-auto text-sm opacity-70 hover:opacity-100"
          onClick={async () => {
            await fetch("/api/logout", { method: "POST" });
            router.replace("/login");
          }}
        >
          Salir
        </button>
      </nav>
      <div className="max-w-5xl mx-auto p-4 sm:p-6">{children}</div>
      <FloatingWidget />
    </div>
  );
}
