import type { Metadata, Viewport } from "next";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";

export const metadata: Metadata = {
  title: "MedPad — سیستم کنترل کارمندها",
  description:
    "پنل مدیریت مدپد: چک‌این/چک‌اوت بازاریاب‌ها با گزارش صوتی، نقشه مشتریان و اعلان‌های لحظه‌ای",
  icons: { icon: "/logo.svg" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: "#0f766e",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="fa" dir="rtl" suppressHydrationWarning>
      <head>
        {/* Vazirmatn — Persian UI font, SELF-HOSTED from /public/fonts.
            v1.9.9: never load this from a CDN again — a render-blocking
            <link> to a filtered/hanging CDN (e.g. jsdelivr on some Iranian
            ISPs) kept the page WHITE / "loading" forever. Local file =
            offline-safe, instant, no external dependency. */}
        <link rel="stylesheet" href="/fonts/vazirmatn.css" />
      </head>
      <body className="antialiased bg-background text-foreground font-sans">
        {children}
        <Toaster />
      </body>
    </html>
  );
}
