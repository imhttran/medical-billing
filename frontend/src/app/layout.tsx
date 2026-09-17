import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  description: "Medical billing for a small primary-care practice.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
