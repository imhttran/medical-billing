import type { Metadata } from "next";
import { Onest, Plus_Jakarta_Sans } from "next/font/google";
import "./globals.css";

// Self-hosted by Next at build time, so the app ships no request to Google.
// The CSS variables land on <html> and globals.css reads them from there.
const onest = Onest({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-onest",
});

const jakarta = Plus_Jakarta_Sans({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-jakarta",
});

export const metadata: Metadata = {
  description: "Medical billing for a small primary-care practice.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" className={`${onest.variable} ${jakarta.variable}`}>
      <body>{children}</body>
    </html>
  );
}
