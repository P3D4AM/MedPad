import type { NextConfig } from "next";
import path from "path";

const nextConfig: NextConfig = {
  output: "standalone",
  // v1.9.12 (audit phase 3): keep bcryptjs EXTERNAL in the standalone output.
  // The offline installer's seed step imports it from
  // .next/standalone/node_modules at runtime; bundling it into the server
  // chunks removed it from node_modules and broke the seed on fresh installs.
  serverExternalPackages: ["bcryptjs"],
  typescript: {
    ignoreBuildErrors: true,
  },
  reactStrictMode: false,
  // socket.io handshake requires the trailing slash on /socket.io/ —
  // Next's 308 normalization breaks it; skip it.
  skipTrailingSlashRedirect: true,
  // realtime proxy — panel connects same-origin /socket.io, Next forwards to :3003
  // (same architecture as the original: socket rides the panel origin, works
  //  through tunnels & LAN without exposing a second port)
  async rewrites() {
    return [
      // engine.io always requests exactly /socket.io/ (trailing slash) — keep it
      { source: "/socket.io", destination: "http://127.0.0.1:3003/socket.io/" },
      { source: "/socket.io/", destination: "http://127.0.0.1:3003/socket.io/" },
      { source: "/socket.io/:path*", destination: "http://127.0.0.1:3003/socket.io/:path*" },
    ];
  },
  // v1.9.3 fix: Turbopack misdetects the workspace root when the installer is
  // unpacked next to another Node project (parent bun.lock/package-lock.json).
  // Pinning the root prevents files leaking into the neighbor project and
  // broken builds — the same bug that was found during E2E testing.
  turbopack: {
    root: path.resolve(__dirname),
  },
};

export default nextConfig;
