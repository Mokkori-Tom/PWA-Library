"use client";

import Image from "next/image";
import { useEffect, useState } from "react";

export default function Home() {
  const [n, setN] = useState(0);

  // Read after mount: a static export renders this HTML at build time, where
  // there is no localStorage to read from.
  useEffect(() => { setN(Number(localStorage.getItem("count") ?? 0)); }, []);
  useEffect(() => { if (n) localStorage.setItem("count", String(n)); }, [n]);

  return (
    <>
      <Image src="/icons/icon-192.png" alt="" width={64} height={64} />
      <p>
        <button onClick={() => setN(n + 1)}>カウント {n}</button>{" "}
        アプリを閉じて開き直しても残れば localStorage が永続化できています。
      </p>
    </>
  );
}
