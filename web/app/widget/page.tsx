import { WidgetScreen } from "@/components/WidgetScreen";
import { requireSession } from "@/lib/session";

export const dynamic = "force-dynamic";

export default async function WidgetPage() {
  await requireSession();
  return <WidgetScreen />;
}
