#!/usr/bin/env python3
"""Generates every sample/test zip into samples/dist/.

The structural cases target the installer rather than the WebView: root
detection, manifest handling, MIME resolution, and the two negative cases that
must be rejected. Written with zipfile because the `zip` CLI refuses to create
the traversal entry that case 09 needs.
"""
import base64
import io
import os
import struct
import zlib
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(ROOT, "dist")


def png(rgb, size=192):
    rows = b"".join(b"\x00" + bytes(rgb) * size for _ in range(size))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9))
            + chunk(b"IEND", b""))


def page(title, body, head="", viewport=True):
    vp = '<meta name="viewport" content="width=device-width, initial-scale=1">' if viewport else ""
    return f"""<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
{vp}
<title>{title}</title>
<style>
  body {{ font-family: system-ui, sans-serif; margin: 0; padding: 20px;
         background: #f6f8fa; color: #111; }}
  h1 {{ font-size: 18px; }}
  li {{ margin: 6px 0; font-size: 14px; }}
  .ok {{ color: #15803d; font-weight: 600; }}
  .ng {{ color: #b91c1c; font-weight: 600; }}
  .warn {{ color: #a16207; font-weight: 600; }}
  hr {{ border: 0; border-top: 1px solid #e3e8ee; margin: 16px 0; }}
  .note {{ font-size: 13px; color: #555; line-height: 1.7; }}
  code {{ background: #e6eaef; padding: 1px 5px; border-radius: 4px;
         overflow-wrap: anywhere; word-break: break-all; }}
</style>
{head}
</head>
<body>
<h1>{title}</h1>
{body}
</body>
</html>
"""


def manifest(**kw):
    import json
    return json.dumps(kw, ensure_ascii=False, indent=2)


def clean():
    if os.path.isdir(DIST):
        for n in os.listdir(DIST):
            if n.endswith(".zip"):
                os.remove(os.path.join(DIST, n))


def write(name, files):
    os.makedirs(DIST, exist_ok=True)
    path = os.path.join(DIST, name)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for entry, data in files.items():
            z.writestr(entry, data if isinstance(data, bytes) else data.encode("utf-8"))
    print(f"  {name:26s} {os.path.getsize(path):6d} B  ({len(files)} entries)")


CASES = []


def case(fn):
    CASES.append(fn)
    return fn


# --------------------------------------------------------------- positive cases

@case
def wrapped():
    """Zipping a folder rather than its contents. Root must be auto-detected."""
    body = "<p>ルートフォルダ <code>my-app/</code> の中に index.html がある zip です。</p>" \
           "<p>この画面が出れば <span class='ok'>ルート自動検出 OK</span>。</p>"
    write("01-wrapped.zip", {
        "my-app/index.html": page("ラップされた zip", body),
        "my-app/manifest.json": manifest(id="test.wrapped", name="01 ラップ zip",
                                         start_url="index.html", display="standalone"),
        "my-app/icons/icon.png": png((0x1f, 0x6f, 0xeb)),
        # Noise macOS adds; must not end up extracted or confuse root detection.
        "__MACOSX/my-app/._index.html": b"\x00\x05\x16\x07",
        "my-app/.DS_Store": b"\x00\x00\x00\x01",
    })


@case
def no_manifest():
    """No manifest: the name comes from <title>, the icon falls back to a tile."""
    body = """
<p>版: <b>rev 2</b>。manifest.json のない zip です。名前もアイコンも
index.html からしか取れません。</p>
<p>一覧での名前が <b>現場メモ &amp; ログ</b>、アイコンが<b>頭文字タイル</b>なら
<span class='ok'>OK</span>。</p>
<ul>
  <li><code>02-no-manifest</code> のまま → &lt;title&gt; を読めていない（ファイル名に落ちている）</li>
  <li><code>現場メモ &amp;amp; ログ</code> → 実体参照を復号していない</li>
</ul>
"""
    write("02-no-manifest.zip", {"index.html": page("現場メモ &amp; ログ", body)})


@case
def no_viewport():
    """No viewport meta: without injection this renders at an assumed 980px."""
    body = """
<p>viewport メタタグを書いていない HTML です。</p>
<ul>
  <li>load 時の clientWidth: <code id="a">-</code></li>
  <li>現在の clientWidth: <code id="b">-</code></li>
</ul>
<p id="v">判定中…</p>
<script>
(function () {
  var a = document.getElementById('a'), b = document.getElementById('b'),
      v = document.getElementById('v'), first = null;
  function w() { return document.documentElement.clientWidth; }
  window.addEventListener('load', function () { first = w(); a.textContent = first + 'px'; });
  // Keeps measuring: a value that only settles later means the injection
  // happened after layout, which is a different result from no injection.
  function tick() {
    var now = w();
    b.textContent = now + 'px';
    if (now >= 600) {
      v.innerHTML = "<span class='ng'>NG</span> " + now + "px のまま。注入されていない";
    } else if (first !== null && first < 600) {
      v.innerHTML = "<span class='ok'>OK</span> 最初から device-width（配信時に HTML へ注入）";
    } else if (first !== null) {
      v.innerHTML = "<span class='warn'>要調査</span> 後から幅が変わった（想定外の経路）";
    }
  }
  setInterval(tick, 300);
  tick();
})();
</script>
"""
    write("03-no-viewport.zip", {
        "index.html": page("viewport なし", body, viewport=False),
        "manifest.json": manifest(id="test.noviewport", name="03 viewport なし",
                                  start_url="index.html"),
    })


@case
def subdir_start():
    """start_url points somewhere other than the root index.html."""
    root = "<p class='ng'>ここは index.html です。start_url が効いていません。</p>"
    start = "<p><span class='ok'>OK</span> start_url = <code>pages/start.html</code> が使われました。</p>"
    write("04-subdir-start.zip", {
        "index.html": page("index.html（誤り）", root),
        "pages/start.html": page("start_url", start),
        "manifest.json": manifest(id="test.starturl", name="04 start_url",
                                  start_url="pages/start.html", display="standalone"),
    })


@case
def fullscreen():
    """display:fullscreen plus theme_color."""
    body = "<p>manifest で <code>display: fullscreen</code> を指定しています。</p>" \
           "<p>ステータスバーとナビゲーションバーが消えていれば <span class='ok'>OK</span>。</p>"
    write("05-fullscreen.zip", {
        "index.html": page("フルスクリーン", body),
        "manifest.json": manifest(id="test.fullscreen", name="05 フルスクリーン",
                                  start_url="index.html", display="fullscreen",
                                  theme_color="#b91c1c"),
    })


@case
def svg_icon():
    """Only an SVG icon. BitmapFactory cannot decode it; must fall back, not crash."""
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="192" height="192">'
           '<rect width="192" height="192" fill="#7c3aed"/></svg>')
    body = "<p>manifest のアイコンが SVG のみの zip です。</p>" \
           "<p>頭文字タイルになり、クラッシュしなければ <span class='ok'>OK</span>。</p>"
    write("06-svg-icon.zip", {
        "index.html": page("SVG アイコン", body),
        "icons/icon.svg": svg,
        "manifest.json": manifest(id="test.svgicon", name="06 SVG アイコン",
                                  start_url="index.html",
                                  icons=[{"src": "icons/icon.svg", "sizes": "192x192",
                                          "type": "image/svg+xml"}]),
    })


@case
def assets():
    """Every MIME type in LocalFilePathHandler that is easy to get wrong."""
    checks = """
<ul id="list"></ul>
<script type="module">
  import { value } from './lib/mod.mjs';
  window.__mod = value;
</script>
<script>
window.addEventListener('load', function () {
  var out = document.getElementById('list');
  function row(name, ok, extra) {
    out.innerHTML += '<li>' + name + ': <span class="' + (ok ? 'ok' : 'ng') + '">' +
                     (ok ? 'OK' : 'NG') + '</span> ' + (extra || '') + '</li>';
  }
  row('CSS (.css)', getComputedStyle(document.body).backgroundColor === 'rgb(255, 251, 235)');
  row('Script (.js)', window.__js === true);
  row('Module (.mjs)', window.__mod === 42);
  row('SVG (.svg)', document.getElementById('svg').naturalWidth > 0);
  row('WebP (.webp)', true, '(画像が出ていれば OK)');
  fetch('data/data.json').then(function (r) { return r.json(); })
    .then(function (j) { row('JSON (.json)', j.ok === true); })
    .catch(function (e) { row('JSON (.json)', false, e.message); });
  fetch('lib/empty.wasm').then(function (r) { return r.arrayBuffer(); })
    .then(function (b) { return WebAssembly.instantiate(b); })
    .then(function () { row('WASM (.wasm)', true); })
    .catch(function (e) { row('WASM (.wasm)', false, e.message); });
  row('Font (.woff2)', document.fonts ? true : false, '(読み込みのみ)');
});
</script>
<img id="svg" src="img/logo.svg" width="48" height="48">
"""
    head = '<link rel="stylesheet" href="css/styles.css"><script src="js/app.js" defer></script>'
    write("07-assets.zip", {
        "index.html": page("MIME 判定", checks, head=head),
        "css/styles.css": "body { background: #fffbeb; }",
        "js/app.js": "window.__js = true;",
        "lib/mod.mjs": "export const value = 42;",
        "lib/empty.wasm": bytes([0, 0x61, 0x73, 0x6d, 1, 0, 0, 0]),
        "data/data.json": '{"ok": true}',
        "img/logo.svg": '<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48">'
                        '<circle cx="24" cy="24" r="22" fill="#0f766e"/></svg>',
        "font/dummy.woff2": b"wOF2\x00\x00\x00\x00",
        "manifest.json": manifest(id="test.assets", name="07 MIME 判定",
                                  start_url="index.html"),
    })


@case
def appshell_sw():
    """The Service Worker shape flagged as a risk: every navigation is answered
    from cache, which can swallow an update.

    Emitted as a matched pair sharing one manifest id, so the update path is
    tested by importing one zip and then the other. Editing this file by hand
    between runs was too easy to get wrong.
    """
    # Byte-identical in both zips on purpose: if sw.js changed, the browser would
    # reinstall the worker on its own and the test would prove nothing.
    sw = """
const CACHE = 'shell-v1';
self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(['index.html'])));
  self.skipWaiting();
});
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));
self.addEventListener('fetch', e => {
  // Deliberately hostile to updates: ANY navigation returns the cached shell.
  if (e.request.mode === 'navigate') {
    e.respondWith(caches.match('index.html').then(hit => hit || fetch(e.request)));
    return;
  }
  e.respondWith(caches.match(e.request).then(hit => hit || fetch(e.request)));
});
"""

    template = """
<p>ナビゲーションを常にキャッシュから返す Service Worker を積んだ zip です。
更新時に古いキャッシュを破棄できるかを見ます。</p>

<p style="font-size:22px; margin:18px 0">ビルド番号: <b>__BUILD__</b></p>
<p>Service Worker: <code id="sw">確認中…</code></p>
<p>origin: <code id="origin"></code></p>
<p>この origin での起動回数: <code id="count">-</code></p>
<p id="verdict"></p>

<hr>
<p class="note">
<b>SW の登録はこのページが自動で行います。操作は不要です。</b><br>
上が「登録済み・制御中」になったら、次の zip を取り込んでください。<br><br>
<b>__OTHER__</b><br><br>
manifest の id が同じなので、新規追加ではなく更新として扱われます。<br>
取り込んだあと <b>__EXPECT__</b> に変われば成功。
<b>__BUILD__</b> のままなら、SW のキャッシュを破棄できていません。<br><br>
2 つの zip はどちら向きにも使えるので、何度でも往復して試せます。
</p>

<script>
(function () {
  // Distinguishes a real update from a duplicate install: only an update keeps
  // the origin, so only then does the counter survive. Without this, a second
  // entry on a fresh origin also shows the new build number and looks like a pass.
  document.getElementById('origin').textContent = location.origin;
  var n = Number(localStorage.getItem('shell-count') || '0') + 1;
  localStorage.setItem('shell-count', String(n));
  document.getElementById('count').textContent = n + ' 回目';
  document.getElementById('verdict').innerHTML = n > 1
    ? "<span class='ok'>同じ origin</span> 更新として取り込まれています"
    : "<span class='warn'>初回</span> この origin では初起動（更新なら 2 回目以降になるはず）";

  var el = document.getElementById('sw');
  if (!('serviceWorker' in navigator)) { el.textContent = '非対応'; return; }
  navigator.serviceWorker.register('sw.js').then(function () {
    function upd() {
      el.textContent = navigator.serviceWorker.controller
        ? '登録済み・制御中（次の zip を取り込んでください）'
        : '登録済み・未制御（一度閉じて開き直すと制御が始まります）';
    }
    upd();
    navigator.serviceWorker.addEventListener('controllerchange', upd);
  }).catch(function (e) { el.textContent = '登録失敗: ' + e.message; });
})();
</script>
"""

    pair = [
        ("BUILD-1", "08a-appshell-BUILD1.zip"),
        ("BUILD-2", "08b-appshell-BUILD2.zip"),
    ]
    for i, (build, name) in enumerate(pair):
        other_build, other_name = pair[1 - i]
        body = (template
                .replace("__BUILD__", build)
                .replace("__OTHER__", other_name)
                .replace("__EXPECT__", other_build))
        write(name, {
            "index.html": page("app shell SW " + build, body),
            "sw.js": sw,
            # Same id in both, so the second import updates the first.
            "manifest.json": manifest(id="test.appshell", name="08 app shell SW",
                                      start_url="index.html"),
        })


@case
def relative_manifest_id():
    """Regression test for generated manifests.

    A real LLM-written manifest used `"id": "./index.html?v=5"`. That id is
    neither unique (every generated app tends to pick something like it) nor
    stable (the version is baked into it), so matching on it verbatim would both
    merge unrelated apps and split successive versions of one.
    """
    def build(title, ident, note):
        body = ("<p>manifest の id: <code>" + ident + "</code></p>"
                "<p>" + note + "</p>")
        return {
            "index.html": page(title, body),
            "manifest.json": manifest(id=ident, name=title, start_url="./index.html"),
        }

    write("11a-relid-A-v1.zip", build(
        "11 相対 id A", "./index.html?v=1",
        "最初に取り込む。一覧に 1 個。"))
    write("11b-relid-B-v1.zip", build(
        "11 相対 id B", "./index.html?v=1",
        "A と <b>同じ id</b> だが名前が違う。<b>別アプリとして 2 個目</b>になれば OK。"
        "A が消えたり上書きされたら重大な不具合。"))
    write("11c-relid-A-v2.zip", build(
        "11 相対 id A", "./index.html?v=2",
        "A と同じ名前で <b>id のバージョンだけ違う</b>。"
        "A の<b>更新</b>になり、一覧が増えなければ OK。"))


@case
def folder_write():
    """Exercises the File System Access shim with standard API calls only.

    Everything except the auto-reconnect on load is spec code, so this doubles as
    a check that generated PWAs work unmodified.
    """
    body = r"""
<p>標準の File System Access API だけで、選んだフォルダに追記します。</p>

<p>診断: <code id="diag">-</code></p>
<p>フォルダ: <code id="dir">未選択</code></p>
<p><button id="pick">フォルダを選ぶ</button>
   <button id="append">1 行追記</button>
   <button id="list">中身を一覧</button></p>
<p id="out"></p>
<pre id="log" style="background:#fff;border:1px solid #e3e8ee;border-radius:8px;
     padding:10px;white-space:pre-wrap;word-break:break-all;font-size:12px"></pre>

<p class="note">
アプリを閉じて開き直しても<b>同じフォルダに追記が続く</b>なら、
SAF の権限が永続化できています。ファイルマネージャで
<code>pwalib-log.txt</code> を直接開いて確認できます。
</p>

<script>
var dirHandle = null;
var out = document.getElementById('out');
var log = document.getElementById('log');

// Distinguishes "the button did nothing" from "the native side never answered".
document.getElementById('diag').textContent =
  'bridge=' + (window.__pwalibFs ? 'あり' : 'なし') +
  ' / shim=' + (window.pwaLibrary && window.pwaLibrary.files ? 'あり' : 'なし');

function show(msg, ok) {
  out.innerHTML = "<span class='" + (ok ? 'ok' : 'ng') + "'>" + msg + "</span>";
}

async function refreshName() {
  document.getElementById('dir').textContent = dirHandle ? dirHandle.name : '未選択';
}

document.getElementById('pick').onclick = async function () {
  try {
    show('呼び出しました。ダイアログを待っています…', true);
    dirHandle = await window.showDirectoryPicker({ mode: 'readwrite' });
    await refreshName();
    show('選択しました', true);
  } catch (e) {
    // Backing out of the system picker is a cancel, not a failure.
    if (e.name === 'AbortError') { show('選択を取り消しました', true); return; }
    show('失敗: ' + e.name + ' — ' + e.message, false);
  }
};

document.getElementById('append').onclick = async function () {
  if (!dirHandle) { show('先にフォルダを選んでください', false); return; }
  try {
    var fh = await dirHandle.getFileHandle('pwalib-log.txt', { create: true });
    var file = await fh.getFile();
    var w = await fh.createWritable({ keepExistingData: true });
    // Spec: the cursor starts at 0 even with keepExistingData.
    await w.seek(file.size);
    await w.write(new Date().toISOString() + ' から追記\n');
    await w.close();

    var after = await fh.getFile();
    log.textContent = await after.text();
    show('追記しました（' + after.size + ' バイト）', true);
  } catch (e) {
    show('失敗: ' + e.name + ' — ' + e.message, false);
  }
};

document.getElementById('list').onclick = async function () {
  if (!dirHandle) { show('先にフォルダを選んでください', false); return; }
  try {
    var names = [];
    for await (var entry of dirHandle.values()) {
      names.push((entry.kind === 'directory' ? '[D] ' : '    ') + entry.name);
    }
    log.textContent = names.join('\n') || '(空)';
    show(names.length + ' 件', true);
  } catch (e) {
    show('失敗: ' + e.name + ' — ' + e.message, false);
  }
};

// Reconnect without prompting. This part is the shim's own extension: the
// standard API has no way to recover a handle without storing it first.
window.addEventListener('load', async function () {
  if (!window.pwaLibrary || !window.pwaLibrary.files) return;
  try {
    var folders = await window.pwaLibrary.files.folders();
    if (folders.length) { dirHandle = folders[0]; await refreshName(); show('前回のフォルダに再接続しました', true); }
  } catch (e) { /* none granted yet */ }
});
</script>
"""
    write("12-folder-write.zip", {
        "index.html": page("フォルダ書き込み", body),
        "manifest.json": manifest(id="test.folderwrite", name="12 フォルダ書き込み",
                                  start_url="index.html", display="standalone"),
    })


@case
def picker_modes():
    """Separates the two meanings of showDirectoryPicker().

    12-folder-write reconnects through pwaLibrary.files.folders(), so it never
    exercises the no-gesture branch of the picker itself. This one calls the
    standard function in both states and reports which one it got, without
    needing DevTools: transient activation expires on its own after a few
    seconds, so a delayed call is a gesture-free call.
    """
    body = r"""
<p>同じ <code>showDirectoryPicker()</code> が、ユーザー操作の有無で意味を変える。
<b>操作あり = 選び直したい</b>ので選択画面を出し、<b>操作なし = 前回の続き</b>なので
許可済みのフォルダを黙って返す。</p>

<p>診断: <code id="diag">-</code></p>
<p>許可済み: <code id="grants">-</code></p>
<p id="load" class="note">-</p>

<p><button id="gesture">1. ユーザー操作で呼ぶ</button>
   <button id="delayed">2. 8 秒後に呼ぶ</button></p>
<p id="out"></p>
<pre id="log" style="background:#fff;border:1px solid #e3e8ee;border-radius:8px;
     padding:10px;white-space:pre-wrap;font-size:12px"></pre>

<p class="note">
1 は<b>選択画面が出る</b>のが正解。2 は<b>出ずに</b>フォルダ名が返るのが正解。
2 を押したあとは <b>8 秒間画面に触らないこと</b>。触ると操作が新しくなり、
1 と同じ扱いになる。
</p>

<script>
var out = document.getElementById('out');
var log = document.getElementById('log');
var ua = navigator.userActivation;

document.getElementById('diag').textContent =
  'bridge=' + (window.__pwalibFs ? 'あり' : 'なし') +
  ' / shim=' + (window.pwaLibrary && window.pwaLibrary.files ? 'あり' : 'なし') +
  ' / userActivation=' + (ua ? 'あり' : 'なし');

function show(msg, ok) {
  out.innerHTML = "<span class='" + (ok ? 'ok' : 'ng') + "'>" + msg + "</span>";
}

function note(line) {
  log.textContent += line + '\n';
}

async function grants() {
  if (!window.pwaLibrary || !window.pwaLibrary.files) return [];
  try { return await window.pwaLibrary.files.folders(); } catch (e) { return []; }
}

async function refreshGrants() {
  var list = await grants();
  document.getElementById('grants').textContent =
    list.length ? list.map(function (h) { return h.name; }).join(', ') : 'なし';
  return list;
}

// Records what the picker did, alongside the activation state it was called in,
// so a wrong answer says which branch ran rather than just failing.
async function callPicker(label) {
  var active = ua ? ua.isActive : '(不明)';
  note(label + ': isActive=' + active + ' で呼び出し');
  try {
    var h = await window.showDirectoryPicker();
    note('  → ' + h.name);
    return h;
  } catch (e) {
    note('  → 失敗 ' + e.name + ' — ' + e.message);
    return null;
  }
}

document.getElementById('gesture').onclick = async function () {
  show('選択画面が出れば OK', true);
  var h = await callPicker('操作あり');
  await refreshGrants();
  if (h) show('選択画面から ' + h.name + ' を選びました', true);
};

document.getElementById('delayed').onclick = function () {
  var left = 8;
  var btn = this;
  btn.disabled = true;
  show('あと ' + left + ' 秒。画面に触らないでください', true);
  var t = setInterval(async function () {
    left -= 1;
    if (left > 0) { show('あと ' + left + ' 秒。画面に触らないでください', true); return; }
    clearInterval(t);
    btn.disabled = false;
    var before = ua ? ua.isActive : null;
    var h = await callPicker('操作なし（8 秒後）');
    if (before === true) {
      show('触ってしまったので判定できません。もう一度', false);
    } else if (h) {
      show('選択画面を出さずに ' + h.name + ' が返れば OK', true);
    } else {
      show('拒否されました。許可済みフォルダが無いのかもしれません', false);
    }
  }, 1000);
};

// A page-load call is the reconnect case, but only when something has been
// granted: with no grant the shim falls through and prompts, and a picker
// appearing by itself on load would just be confusing.
window.addEventListener('load', async function () {
  var list = await refreshGrants();
  var el = document.getElementById('load');
  if (!list.length) {
    el.innerHTML = '<span class="warn">この起動では許可済みフォルダが無かったので、'
      + '読み込み時の呼び出しは省略しました。1 で選んだあと、'
      + '<b>閉じて開き直す</b>とここに再接続の結果が出ます。</span>';
    return;
  }
  el.textContent = '読み込み時に呼び出し中…';
  var h = await callPicker('読み込み時');
  el.innerHTML = h
    ? '<span class="ok">読み込み時: 選択画面なしで ' + h.name + ' に再接続</span>'
    : '<span class="ng">読み込み時: 再接続できませんでした</span>';
});
</script>
"""
    write("13-picker-modes.zip", {
        "index.html": page("ピッカーの 2 つの意味", body),
        "manifest.json": manifest(id="test.pickermodes", name="13 ピッカーの意味",
                                  start_url="index.html", display="standalone"),
    })


@case
def downloads():
    """Saving what a page exports.

    Shaped after fieldform, which is what exposed the gap: a Blob handed to
    <a download>, revoked a second and a half later, in a page whose CSP
    forbids fetch(). Reading the blob back over the network is the obvious
    implementation and the one that cannot work here.
    """
    body = r"""
<p>ページが書き出したファイルを保存できるか。<b>fieldform と同じ形</b>で、
blob URL を <code>&lt;a download&gt;</code> に渡し、1.5 秒後に revoke する。</p>

<p>版: <code>rev 2</code>（この表示が無ければ古い zip が入っている）</p>
<p>診断: <code id="diag">-</code></p>

<p><button id="csv">CSV を書き出す</button>
   <button id="big">2 MB を書き出す</button>
   <button id="data">data URL で書き出す</button></p>
<p><a id="own" href="data/notes.txt" download="notes.txt">アプリ内のファイルを保存</a>
   ・
   <a id="pct" href="data:text/plain;charset=utf-8,%E3%83%91%E3%83%BC%E3%82%BB%E3%83%B3%E3%83%88%E3%82%A8%E3%83%B3%E3%82%B3%E3%83%BC%E3%83%89%0A"
      download="percent-encoded.txt">data URL（base64 以外）</a></p>
<p id="out"></p>

<p class="note">
どれも<b>保存先を選ぶ画面</b>が出て、選んだ場所にファイルができるのが正解。
中身はファイルマネージャで確認する。2 MB のものは分割して渡しているので、
途中で切れていないかサイズで見る（<b>2,097,152 バイト</b>）。
</p>

<script>
var out = document.getElementById('out');

function show(msg, ok) {
  out.innerHTML = "<span class='" + (ok ? 'ok' : 'ng') + "'>" + msg + "</span>";
}

// Without this a throwing handler looks exactly like a dead button, which is
// how btoa() refusing non-Latin-1 text got read as a bug in the container.
window.addEventListener('error', function (e) {
  show('ページ側で例外: ' + (e.message || e.type), false);
});

// The page's own CSP is the reason the shim reads blobs with FileReader
// instead of fetching them. Reported here so a failure is not mistaken for a
// broken bridge.
(function () {
  var url = URL.createObjectURL(new Blob(['x']));
  var line = 'blob URL への fetch: ';
  fetch(url).then(function () {
    document.getElementById('diag').textContent = line + '通る';
  }).catch(function (e) {
    document.getElementById('diag').textContent = line + '遮断 (' + e.name + ')';
  }).then(function () { URL.revokeObjectURL(url); });
})();

// Deliberately the same as fieldform's helper, revoke delay included.
function download(filename, text, mime) {
  var blob = new Blob([text], { type: (mime || 'text/plain') + ';charset=utf-8' });
  var url = URL.createObjectURL(blob);
  var a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(function () { URL.revokeObjectURL(url); }, 1500);
  show(filename + ' を書き出しました。保存先を選ぶ画面が出れば OK', true);
}

document.getElementById('csv').onclick = function () {
  download('sample.csv', '番号,名前\n1,テスト\n2,ダウンロード\n', 'text/csv');
};

document.getElementById('big').onclick = function () {
  var block = new Array(1025).join('x');
  var parts = [];
  for (var i = 0; i < 2048; i++) parts.push(block);
  download('big.txt', parts.join(''), 'text/plain');
};

document.getElementById('data').onclick = function () {
  // btoa() takes Latin-1 only, so encode to UTF-8 bytes first. Passing
  // Japanese straight in throws, and then the click never happens at all.
  var bytes = new TextEncoder().encode('data URL からの保存\n');
  var binary = '';
  for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  var a = document.createElement('a');
  a.href = 'data:text/plain;base64,' + btoa(binary);
  a.download = 'from-data-url.txt';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  show('data URL で書き出しました', true);
};
</script>
"""
    head = ('<meta http-equiv="Content-Security-Policy" '
            'content="default-src \'self\'; script-src \'self\' \'unsafe-inline\'; '
            'connect-src \'none\'">')
    write("14-download.zip", {
        "index.html": page("ダウンロード", body, head=head),
        "data/notes.txt": "アプリ内のファイルをそのまま保存できるかの確認用。\n",
        "manifest.json": manifest(id="test.download", name="14 ダウンロード",
                                  start_url="index.html", display="standalone"),
    })


# --------------------------------------------- manifest-less metadata (02, 15, 16)
# These ship without a manifest, so the name and the icon can only come out of
# index.html. 02 covers the title; the two below cover what it cannot.


@case
def link_icon():
    """<link rel="icon"> is followed, and an unusable candidate is stepped over.

    rev 2. The skipped candidate used to be a truncated PNG, on the assumption
    that a decoder would reject it. BitmapFactory does not — it returns the rows
    it managed to read (HANDOVER "4."), so the sample proved nothing. It is now a
    file that is not an image at all, which fails at the bounds pass.

    Three links, ordered so that only the right behaviour reaches the right icon:
      favicon.ico       garbage bytes, in a format BitmapFactory cannot read
      not-an-image.png  the largest declared size, but plain text underneath
      logo-a1b2c3.png   the one that must actually win
    """
    body = """
<p>版: <b>rev 2</b>。manifest.json はありません。アイコンは
<code>&lt;link rel="icon"&gt;</code> からしか辿れません。</p>
<p>一覧のアイコンが <b>青緑（teal）の四角</b>なら <span class='ok'>OK</span>。</p>
<ul>
  <li>頭文字タイル → 512 の候補で諦めている（順に試せていない）</li>
  <li>黒や赤 → rev 1 の zip を取り込んでいる。作り直すこと</li>
</ul>
<p class='note'>512 として宣言されている <code>assets/not-an-image.png</code> は
中身がただのテキストで、寸法すら読めない。192 の青緑に辿り着くには、
候補をひとつ飛ばす必要がある。</p>
"""
    head = ('<link rel="shortcut icon" href="favicon.ico">\n'
            '<link rel="icon" type="image/png" sizes="512x512" href="assets/not-an-image.png">\n'
            '<link rel="icon" type="image/png" sizes="192x192" href="assets/logo-a1b2c3.png">')
    write("15-link-icon.zip", {
        "index.html": page("リンクされたアイコン", body, head=head),
        "favicon.ico": b"\x00\x00\x01\x00" + b"not really an icon",
        # Not an image in any format: BitmapFactory cannot even read bounds off
        # it, so it fails at the first pass rather than half way through.
        "assets/not-an-image.png": b"png is in the name only; these are just bytes.\n" * 8,
        "assets/logo-a1b2c3.png": png((13, 148, 136)),
    })


@case
def title_generic():
    """A scaffold's default title must not become the app's name."""
    body = """
<p>版: <b>rev 1</b>。manifest なし、<code>&lt;title&gt;Document&lt;/title&gt;</code>。</p>
<p>一覧での名前が <b>16-title-generic</b>（ファイル名）なら <span class='ok'>OK</span>。</p>
<p><b>Document</b> と出ていたら、雛形の既定値をそのまま名前に採用しています。</p>
"""
    write("16-title-generic.zip", {"index.html": page("Document", body)})


@case
def data_icon():
    """The icon is carried inside the manifest itself, as a data: URI.

    Two of them, so that skipping still has to work: the SVG is declared the
    larger and sorts first, and cannot be rasterised. Nothing in the zip is an
    icon file, so a letter tile means neither data URI was read.
    """
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512">'
           '<rect width="512" height="512" fill="#dc2626"/></svg>')
    svg_uri = "data:image/svg+xml;base64," + base64.b64encode(svg.encode()).decode()
    png_uri = "data:image/png;base64," + base64.b64encode(png((234, 88, 12))).decode()

    body = """
<p>版: <b>rev 1</b>。アイコンのファイルは 1 つも入っていません。manifest の
<code>icons</code> に <code>data:</code> URI が 2 つ書いてあるだけです。</p>
<p>一覧のアイコンが <b>オレンジの四角</b>なら <span class='ok'>OK</span>。</p>
<ul>
  <li>頭文字タイル → data URI を復号していない</li>
  <li><span class='ng'>赤</span>い四角 → SVG の data URI をラスタライズできて
      しまっている（残作業 4 が済んでいれば正しい挙動）</li>
</ul>
<p class='note'>先に並ぶのは 512 と宣言された SVG の方。オレンジ（192 の PNG）に
辿り着くには、読めない data URI をひとつ飛ばす必要がある。</p>
"""
    write("17-data-icon.zip", {
        "index.html": page("data URI のアイコン", body),
        "manifest.json": manifest(
            id="test.dataicon", name="17 data URI アイコン", start_url="index.html",
            icons=[{"src": svg_uri, "sizes": "512x512", "type": "image/svg+xml"},
                   {"src": png_uri, "sizes": "192x192", "type": "image/png"}]),
    })


# ------------------------------------------------- same name, no other identity

@case
def same_name():
    """Two builds of one app that share only a name.

    No manifest, so there is no id to match on, and the bytes differ, so the
    hash does not match either. All the installer has is the <title>, which is
    a hint and not an identity — the user gets asked which it is.

    The counter is what makes the answer visible: updating reuses the app's
    uuid, and therefore its origin, so localStorage survives. Adding as a new
    app gets a fresh origin and starts over.
    """
    def build(rev, colour, note):
        body = f"""
<p style="font-size:40px;margin:8px 0;color:{colour}"><b>{rev}</b></p>
<p>版: <b>{rev}</b>。manifest.json はありません。<code>&lt;title&gt;</code> だけが
「棚卸しメモ」と名乗っています。</p>
<p>起動回数: <code id="n">-</code></p>
<p class='note'>{note}</p>
<script>
(function () {{
  var n = (parseInt(localStorage.getItem('runs') || '0', 10) || 0) + 1;
  localStorage.setItem('runs', String(n));
  document.getElementById('n').textContent = n;
}})();
</script>
"""
        return {"index.html": page("棚卸しメモ", body)}

    write("18a-samename-v1.zip", build(
        "v1", "#2563eb",
        "先にこちらを取り込む。何も尋ねられずに追加されるのが正しい。"))
    write("18b-samename-v2.zip", build(
        "v2", "#b91c1c",
        "18a のあとに取り込むと「同じ名前のアプリがあります」と尋ねられる。"
        "更新を選べば起動回数が引き継がれ、別アプリとして追加を選べば 1 から始まる。"))


# --------------------------------------------------------------- negative cases

@case
def traversal():
    """Zip Slip. Must be refused before anything is written."""
    write("09-traversal-MUST-FAIL.zip", {
        "index.html": page("これは表示されてはいけない", "<p class='ng'>展開されてしまいました</p>"),
        "manifest.json": manifest(id="test.traversal", name="09 traversal"),
        "../../databases/pwa_library.db": b"OVERWRITTEN",
        "../../../../data/data/com.example.pwalibrary/files/pwned.txt": b"pwned",
    })


@case
def no_index():
    """No index.html anywhere. Must be refused with a readable message."""
    write("10-no-index-MUST-FAIL.zip", {
        "readme.txt": "index.html はありません",
        "pages/about.html": page("about", "<p>index.html なし</p>"),
    })


def bundle_dir(name, src):
    """Zips samples/<src>/ as-is (hello, diag, snake)."""
    files = {}
    for base, _, names in os.walk(src):
        for n in names:
            if n == ".DS_Store":
                continue
            full = os.path.join(base, n)
            files[os.path.relpath(full, src)] = open(full, "rb").read()
    write(name, files)


def syntax_check():
    """Parses every inline script in the generated zips.

    A JS syntax error makes the browser discard the whole <script> block
    silently: no console entry the user can see, nothing runs, and the page just
    sits there. That is expensive to diagnose on a phone, so catch it here.
    """
    import re
    import shutil
    import subprocess
    import tempfile
    import zipfile

    node = shutil.which("node")
    if not node:
        print("  (node が無いので構文チェックは省略)")
        return

    failures = 0
    for name in sorted(os.listdir(DIST)):
        if not name.endswith(".zip"):
            continue
        with zipfile.ZipFile(os.path.join(DIST, name)) as z:
            for entry in z.namelist():
                if not entry.endswith((".html", ".js", ".mjs")):
                    continue
                text = z.read(entry).decode("utf-8", "replace")
                blocks = ([text] if entry.endswith((".js", ".mjs"))
                          else re.findall(r"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>",
                                          text, re.S))
                for i, code in enumerate(blocks):
                    if not code.strip():
                        continue
                    with tempfile.NamedTemporaryFile("w", suffix=".mjs", delete=False) as f:
                        f.write(code)
                        tmp = f.name
                    r = subprocess.run([node, "--check", tmp],
                                       capture_output=True, text=True)
                    os.unlink(tmp)
                    if r.returncode != 0:
                        failures += 1
                        print("  SYNTAX ERROR  %s :: %s [%d]" % (name, entry, i))
                        print("   ", r.stderr.strip().splitlines()[-3:])
    print("  構文チェック: %s" % ("NG %d 件" % failures if failures else "全て OK"))


if __name__ == "__main__":
    print("building samples ->", DIST)
    clean()
    bundle_dir("hello-pwa.zip", os.path.join(ROOT, "hello"))
    bundle_dir("diag.zip", os.path.join(ROOT, "diag"))
    bundle_dir("snake.zip", os.path.join(ROOT, "snake"))
    for fn in CASES:
        fn()
    syntax_check()
    print("done")
