import type { NextConfig } from "next";

// A static export, the way a Next app is shipped when there is no server.
// Left on the default routing: `out/about.html` rather than
// `out/about/index.html`, since that is the layout a plain host has to cope
// with and therefore the one worth testing.
const nextConfig: NextConfig = {
  output: "export",
  images: { unoptimized: true },
};

export default nextConfig;
