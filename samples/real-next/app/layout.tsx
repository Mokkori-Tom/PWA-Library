import type { Metadata } from "next";
import Link from "next/link";
import Diag from "@/components/Diag";
import "./globals.css";

export const metadata: Metadata = {
  title: "実 PWA テスト (Next)",
  description: "Next の静的出力がそのまま動くかの確認",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ja">
      <body>
        <main>
          <h1>実 PWA テスト (Next)</h1>
          <Diag />
          <nav>
            <Link href="/">ホーム</Link> | <Link href="/about">About</Link> |{" "}
            <Link href="/heavy">遅延チャンク</Link>
          </nav>
          {children}
        </main>
      </body>
    </html>
  );
}
