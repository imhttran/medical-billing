import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // All browser traffic targets this server only: /api/* is proxied server-side
  // to the backend API (API_URL, default http://localhost:8080). The API is
  // therefore never exposed to the browser directly — same-origin fetches, no CORS.
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${process.env.API_URL || "http://localhost:8080"}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
