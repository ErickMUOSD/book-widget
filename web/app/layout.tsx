import type { Metadata, Viewport } from "next";
import "@fontsource/press-start-2p";
import "./globals.css";

export const metadata: Metadata = {
  title: "Book Widget",
  description: "Un personaje pixel art y un pre-spoiler misterioso del siguiente capítulo de tu libro.",
  manifest: "/manifest.webmanifest",
  icons: { icon: "/icon-192.png", apple: "/icon-192.png" },
};

export const viewport: Viewport = {
  themeColor: "#0f1117",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="es">
      <body>{children}</body>
    </html>
  );
}
