import type { MetadataRoute } from "next";

// Next emits this at /manifest.webmanifest, not manifest.json. A static
// export needs the route pinned, or the build refuses to collect it.
export const dynamic = "force-static";

export default function manifest(): MetadataRoute.Manifest {
  return {
    id: "real.next.export",
    name: "実 PWA テスト (Next 静的出力)",
    short_name: "Next export",
    description: "next build --output export の素の出力",
    start_url: "/",
    display: "standalone",
    background_color: "#ffffff",
    theme_color: "#111827",
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  };
}
