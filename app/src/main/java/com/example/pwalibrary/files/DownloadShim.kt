package com.example.pwalibrary.files

/**
 * JavaScript half of download support.
 *
 * A mini-app exports a file the ordinary way — build a Blob, hand its object
 * URL to `<a download>`, click it — and WebView routes that to the app's
 * DownloadListener with nothing but a `blob:` URL. Two things are missing by
 * then, and both have to be collected here, in the page:
 *
 *  - **The bytes.** Only the page can read its own blob. Fetching the URL is
 *    the usual trick, but a page is free to forbid it: fieldform ships
 *    `connect-src 'none'`, which blocks `fetch(blob:)` while leaving FileReader
 *    on the Blob alone. So the Blob itself is kept when it is created, and read
 *    with FileReader.
 *  - **The file name.** DownloadListener is never told the `download`
 *    attribute, so without recording it every export would land as
 *    `downloadfile.bin`.
 *
 * A `data:` URL is handled here outright: WebView routes neither the click nor
 * a download to the app, so the button would do nothing at all.
 */
object DownloadShim {

    const val JS = """
(function () {
  var D = window.__pwalibDl;
  if (!D) return;

  var blobs = {};   // object URL -> Blob
  var names = {};   // href -> file name from <a download>
  var seq = 0;
  var KEEP_AFTER_REVOKE = 60000;

  // Pages revoke the URL moments after clicking (fieldform waits 1.5s), while
  // the save dialog stays up for as long as the user takes. Holding our own
  // reference is what keeps the export alive across that.
  if (window.URL && URL.createObjectURL) {
    var create = URL.createObjectURL.bind(URL);
    var revoke = URL.revokeObjectURL.bind(URL);
    URL.createObjectURL = function (obj) {
      var url = create(obj);
      if (typeof Blob !== 'undefined' && obj instanceof Blob) blobs[url] = obj;
      return url;
    };
    URL.revokeObjectURL = function (url) {
      revoke(url);
      setTimeout(function () { delete blobs[url]; delete names[url]; }, KEEP_AFTER_REVOKE);
    };
  }

  // Capture phase, so the name is recorded even if the page stops the event.
  document.addEventListener('click', function (e) {
    var node = e.target;
    while (node && node.nodeType === 1 && node.tagName !== 'A') node = node.parentNode;
    if (!node || node.nodeType !== 1) return;
    var href = node.getAttribute('href');
    var name = node.getAttribute('download');
    if (!href || !name) return;
    names[href] = name;

    // A data: URL click never reaches DownloadListener — WebView drops it, and
    // the button simply does nothing — so this one is handled here instead of
    // waiting to be asked.
    if (href.indexOf('data:') === 0) {
      var blob = blobFromDataUrl(href);
      if (!blob) return;
      e.preventDefault();
      send('js' + (++seq), blob, name);
    }
  }, true);

  function blobFromDataUrl(url) {
    var comma = url.indexOf(',');
    if (comma < 0) return null;
    var header = url.substring(5, comma);
    var body = url.substring(comma + 1);
    var mime = header.split(';')[0] || 'application/octet-stream';
    try {
      var bytes;
      if (header.indexOf(';base64') >= 0) {
        var binary = atob(body);
        bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      } else {
        bytes = new TextEncoder().encode(decodeURIComponent(body));
      }
      return new Blob([bytes], { type: mime });
    } catch (e) {
      return null;
    }
  }

  window.__pwalibDownloadName = function (url) { return names[url] || ''; };

  window.__pwalibReadBlob = function (url, id) {
    var blob = blobs[url];
    if (!blob) { D.fail(id, 'データが見つかりません'); return; }
    send(id, blob, names[url] || '');
  };

  function send(id, blob, name) {
    var CHUNK = 262144;
    var offset = 0;
    D.open(id, name, blob.type || '', String(blob.size));

    // Sliced rather than read whole: an export can be megabytes, and each
    // slice crosses the bridge as its own base64 string.
    function next() {
      if (offset >= blob.size) { D.done(id); return; }
      var reader = new FileReader();
      reader.onload = function () {
        var text = String(reader.result);
        var comma = text.indexOf(',');
        try { D.chunk(id, comma < 0 ? '' : text.substring(comma + 1)); }
        catch (e) { D.fail(id, String(e)); return; }
        offset += CHUNK;
        next();
      };
      reader.onerror = function () { D.fail(id, '読み出しに失敗しました'); };
      reader.readAsDataURL(blob.slice(offset, offset + CHUNK));
    }
    next();
  }
})();
"""
}
