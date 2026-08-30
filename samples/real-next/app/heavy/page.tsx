"use client";

import dynamic from "next/dynamic";

const Heavy = dynamic(() => import("@/components/Heavy"), {
  ssr: false,
  loading: () => <p>読み込み中…</p>,
});

export default function HeavyPage() {
  return <Heavy />;
}
