"use client";

import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

const BUILD = process.env.NEXT_PUBLIC_BUILD_ID ?? "BUILD-?";

export default function Diag() {
  const pathname = usePathname();
  const [sw, setSw] = useState("確認中…");
  const [origin, setOrigin] = useState("-");

  useEffect(() => {
    setOrigin(location.origin);
    if (!("serviceWorker" in navigator)) { setSw("未対応"); return; }
    navigator.serviceWorker.getRegistration().then((r) =>
      setSw(!r ? "未登録（Next は SW を吐かない）" : navigator.serviceWorker.controller ? "登録済み・制御中" : "登録済み・未制御")
    );
  }, []);

  return (
    <>
      <ul>
        <li>ビルド: <b>{BUILD}</b></li>
        <li>isSecureContext: {String(typeof window !== "undefined" && window.isSecureContext)}</li>
        <li>Service Worker: {sw}</li>
        <li>origin: <code>{origin}</code></li>
      </ul>
      <p>
        現在のパス: <code>{pathname}</code>{" "}
        <button onClick={() => location.reload()}>このパスで再読み込み</button>
      </p>
    </>
  );
}
