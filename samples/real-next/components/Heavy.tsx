export default function Heavy() {
  const sum = Array.from({ length: 50000 }, (_, i) => i).reduce((a, b) => a + b, 0);
  return <p className="ok">遅延チャンクを読み込みました（計算結果 {sum.toLocaleString()}）</p>;
}
