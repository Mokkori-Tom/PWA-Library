import { Suspense, lazy, useEffect, useState } from 'react'
import { Link, Route, Routes, useLocation } from 'react-router-dom'
import reactLogo from './assets/react.svg'

const Heavy = lazy(() => import('./Heavy'))

const BUILD = import.meta.env.VITE_BUILD_ID ?? 'BUILD-?'

function Diag() {
  const [sw, setSw] = useState('確認中…')
  useEffect(() => {
    if (!('serviceWorker' in navigator)) { setSw('未対応'); return }
    navigator.serviceWorker.getRegistration().then((r) => {
      setSw(!r ? '未登録' : navigator.serviceWorker.controller ? '登録済み・制御中' : '登録済み・未制御')
    })
  }, [])
  return (
    <ul>
      <li>ビルド: <b>{BUILD}</b></li>
      <li>isSecureContext: {String(window.isSecureContext)}</li>
      <li>Service Worker: {sw}</li>
      <li>origin: <code>{location.origin}</code></li>
    </ul>
  )
}

function Home() {
  const [n, setN] = useState(() => Number(localStorage.getItem('count') ?? 0))
  useEffect(() => { localStorage.setItem('count', String(n)) }, [n])
  return (
    <>
      <img src={reactLogo} width={64} height={64} alt="" />
      <p>
        <button onClick={() => setN(n + 1)}>カウント {n}</button>
        {' '}アプリを閉じて開き直しても残れば localStorage が永続化できています。
      </p>
    </>
  )
}

export default function App() {
  const { pathname } = useLocation()
  return (
    <main>
      <h1>実 PWA テスト</h1>
      <Diag />
      <nav>
        <Link to="/">ホーム</Link> | <Link to="/about">About</Link> |{' '}
        <Link to="/heavy">遅延チャンク</Link>
      </nav>
      <p>
        現在のパス: <code>{pathname}</code>{' '}
        <button onClick={() => location.reload()}>このパスで再読み込み</button>
      </p>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/about" element={<p>ルーティングは動いています。ここで再読み込みすると history フォールバックの確認になります。</p>} />
        <Route path="/heavy" element={<Suspense fallback={<p>読み込み中…</p>}><Heavy /></Suspense>} />
        <Route path="*" element={<p className="ng">404: このパスは解決できませんでした</p>} />
      </Routes>
    </main>
  )
}
