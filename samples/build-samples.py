#!/usr/bin/env python3
"""Generates every sample/test zip into samples/dist/.

The structural cases target the installer rather than the WebView: root
detection, manifest handling, MIME resolution, and the two negative cases that
must be rejected. Written with zipfile because the `zip` CLI refuses to create
the traversal entry that case 09 needs.
"""
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
    """No manifest at all: name falls back to the file name, icon to a letter tile."""
    body = "<p>manifest.json のない zip です。</p>" \
           "<p>一覧での名前が <code>02-no-manifest</code>、アイコンが頭文字タイルになっていれば " \
           "<span class='ok'>フォールバック OK</span>。</p>"
    write("02-no-manifest.zip", {"index.html": page("manifest なし", body)})


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
