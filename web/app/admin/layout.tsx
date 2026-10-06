import { AdminShell } from "@/components/AdminShell";
import { requireSession } from "@/lib/session";

export const dynamic = "force-dynamic";

export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  await requireSession();
  return <AdminShell>{children}</AdminShell>;
}
