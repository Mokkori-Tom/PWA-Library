package io.github.mokkori_tom.pwalibrary.files

/**
 * JavaScript half of the File System Access shim.
 *
 * Injected into every HTML page this app serves, ahead of the page's own
 * scripts, so code generated against the standard API runs unchanged.
 *
 * Two deliberate deviations from the spec, both to satisfy "keep writing to the
 * same folder across launches":
 *
 *  - showDirectoryPicker() prompts when it is called from a user gesture, and
 *    returns the folder already granted to this app when it is not. A call on
 *    page load is a reconnect — SAF's permission is persistent, so re-picking
 *    every launch would be pointless friction — while a call from a button is
 *    the user asking to choose, and must always offer the picker.
 *  - queryPermission()/requestPermission() always report "granted", because by
 *    the time a handle exists the underlying SAF permission is persisted.
 */
object FileSystemShim {

    const val PATH = "fs.js"

    /** ASCII only, so it survives the byte-preserving splice into the page. */
    const val TAG = "<script src=\"/__pwalib__/fs.js\"></script>"

    const val JS = """
(function () {
  var N = window.__pwalibFs;
  if (!N) return;

  var seq = 0;
  var pending = {};

  // Answer path for requestDirectory, which needs an Activity result.
  window.__pwalibResolve = function (id, json) {
    var p = pending[id];
    delete pending[id];
    if (!p) return;
    var r;
    try { r = JSON.parse(json); } catch (e) { p.reject(mkError(null)); return; }
    if (r.ok) p.resolve(r); else p.reject(mkError(r));
  };

  function mkError(r) {
    var e = new Error((r && r.error) || 'フォルダにアクセスできません');
    e.name = (r && r.name) || 'NotAllowedError';
    return e;
  }

  function call(json) {
    var r;
    try { r = JSON.parse(json); } catch (e) { throw mkError(null); }
    if (!r.ok) throw mkError(r);
    return r;
  }

  function hide(obj, name, value) {
    Object.defineProperty(obj, name, {
      value: value, enumerable: false, writable: true, configurable: true
    });
  }

  function toBase64(bytes) {
    var s = '', CHUNK = 0x8000;
    for (var i = 0; i < bytes.length; i += CHUNK) {
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
    }
    return btoa(s);
  }

  function fromBase64(str) {
    var bin = atob(str || '');
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  function bytesOf(data) {
    if (data == null) return Promise.resolve(new Uint8Array(0));
    if (typeof data === 'string') return Promise.resolve(new TextEncoder().encode(data));
    if (typeof Blob !== 'undefined' && data instanceof Blob) {
      return data.arrayBuffer().then(function (b) { return new Uint8Array(b); });
    }
    if (data instanceof ArrayBuffer) return Promise.resolve(new Uint8Array(data));
    if (ArrayBuffer.isView(data)) {
      return Promise.resolve(new Uint8Array(data.buffer, data.byteOffset, data.byteLength));
    }
    return Promise.resolve(new TextEncoder().encode(String(data)));
  }

  // -------------------------------------------------------------- writable

  function makeWritable(grant, path, keepExisting) {
    var buf = new Uint8Array(0), pos = 0, closed = false;

    function grow(size) {
      if (size <= buf.length) return;
      var next = new Uint8Array(size);
      next.set(buf);
      buf = next;
    }

    function put(bytes, at) {
      grow(at + bytes.length);
      buf.set(bytes, at);
      pos = at + bytes.length;
    }

    var w = {};

    hide(w, 'write', function (data) {
      if (closed) return Promise.reject(mkError({ name: 'InvalidStateError', error: '閉じています' }));
      var chunk = data, at = pos;
      if (data && typeof data === 'object' && typeof data.type === 'string' && !(data instanceof Blob)) {
        if (data.type === 'seek') { pos = data.position || 0; return Promise.resolve(); }
        if (data.type === 'truncate') { return w.truncate(data.size || 0); }
        chunk = data.data;
        if (typeof data.position === 'number') at = data.position;
      }
      return bytesOf(chunk).then(function (b) { put(b, at); });
    });

    hide(w, 'seek', function (p) { pos = p || 0; return Promise.resolve(); });

    hide(w, 'truncate', function (size) {
      var next = new Uint8Array(size || 0);
      next.set(buf.subarray(0, Math.min(size || 0, buf.length)));
      buf = next;
      if (pos > buf.length) pos = buf.length;
      return Promise.resolve();
    });

    // Buffered until close: SAF has no partial-write mode that survives an
    // interrupted stream cleanly, so one atomic write is safer.
    hide(w, 'close', function () {
      if (closed) return Promise.resolve();
      closed = true;
      try { call(N.write(grant, path, toBase64(buf), false)); return Promise.resolve(); }
      catch (e) { return Promise.reject(e); }
    });

    hide(w, 'abort', function () { closed = true; return Promise.resolve(); });

    if (keepExisting) {
      // Cursor stays at 0 even with keepExistingData, per spec; appending code
      // is expected to seek(file.size) first.
      try { buf = fromBase64(call(N.read(grant, path)).data); pos = 0; }
      catch (e) { /* new file */ }
    }
    return w;
  }

  // ---------------------------------------------------------------- handles

  function common(h, grant, path, kind) {
    h.kind = kind;
    h.__pwalib = { grant: grant, path: path, kind: kind };
    hide(h, 'isSameEntry', function (other) {
      return Promise.resolve(
        !!other && !!other.__pwalib &&
        other.__pwalib.grant === grant && other.__pwalib.path === path
      );
    });
    hide(h, 'queryPermission', function () { return Promise.resolve('granted'); });
    hide(h, 'requestPermission', function () { return Promise.resolve('granted'); });
  }

  function fileHandle(grant, path, name) {
    var h = { name: name };
    common(h, grant, path, 'file');

    hide(h, 'getFile', function () {
      try {
        var r = call(N.read(grant, path));
        var bytes = fromBase64(r.data);
        return Promise.resolve(new File([bytes], name, {
          type: r.type || '', lastModified: r.lastModified || Date.now()
        }));
      } catch (e) { return Promise.reject(e); }
    });

    hide(h, 'createWritable', function (opts) {
      return Promise.resolve(makeWritable(grant, path, !!(opts && opts.keepExistingData)));
    });

    return h;
  }

  function dirHandle(grant, path, name) {
    var h = { name: name };
    common(h, grant, path, 'directory');

    function child(n) { return path ? path + '/' + n : n; }

    hide(h, 'getFileHandle', function (n, opts) {
      try {
        var p = child(n);
        var st = call(N.stat(grant, p)).entry;
        if (!st) {
          if (!(opts && opts.create)) {
            throw mkError({ name: 'NotFoundError', error: n + ' がありません' });
          }
          call(N.write(grant, p, '', false));
        } else if (st.kind !== 'file') {
          throw mkError({ name: 'TypeMismatchError', error: n + ' はフォルダです' });
        }
        return Promise.resolve(fileHandle(grant, p, n));
      } catch (e) { return Promise.reject(e); }
    });

    hide(h, 'getDirectoryHandle', function (n, opts) {
      try {
        var p = child(n);
        var st = call(N.stat(grant, p)).entry;
        if (!st) {
          if (!(opts && opts.create)) {
            throw mkError({ name: 'NotFoundError', error: n + ' がありません' });
          }
          call(N.mkdir(grant, p));
        } else if (st.kind !== 'directory') {
          throw mkError({ name: 'TypeMismatchError', error: n + ' はファイルです' });
        }
        return Promise.resolve(dirHandle(grant, p, n));
      } catch (e) { return Promise.reject(e); }
    });

    hide(h, 'removeEntry', function (n, opts) {
      try { call(N.remove(grant, child(n), !!(opts && opts.recursive))); return Promise.resolve(); }
      catch (e) { return Promise.reject(e); }
    });

    function iterator(project) {
      return function () {
        var items = null, i = 0;
        var it = {
          next: function () {
            if (!items) {
              try { items = call(N.list(grant, path)).entries; }
              catch (e) { return Promise.reject(e); }
            }
            if (i >= items.length) return Promise.resolve({ done: true, value: undefined });
            return Promise.resolve({ done: false, value: project(items[i++]) });
          }
        };
        it[Symbol.asyncIterator] = function () { return it; };
        return it;
      };
    }

    function handleFor(e) {
      var p = child(e.name);
      return e.kind === 'directory' ? dirHandle(grant, p, e.name) : fileHandle(grant, p, e.name);
    }

    hide(h, 'keys', iterator(function (e) { return e.name; }));
    hide(h, 'values', iterator(handleFor));
    hide(h, 'entries', iterator(function (e) { return [e.name, handleFor(e)]; }));
    hide(h, Symbol.asyncIterator, iterator(function (e) { return [e.name, handleFor(e)]; }));
    hide(h, 'resolve', function () { return Promise.resolve(null); });

    return h;
  }

  // ----------------------------------------------------------------- picker

  function hasActivation() {
    var ua = navigator.userActivation;
    // Absent on older WebViews. Prompting when it was not wanted is recoverable;
    // silently reusing a folder the user cannot change is not.
    return ua ? ua.isActive : true;
  }

  function pick(force) {
    return new Promise(function (resolve, reject) {
      // Without a gesture this is a page-load reconnect, not a request to choose.
      if (!force && !hasActivation()) {
        try {
          var granted = call(N.listGrants()).grants;
          if (granted.length > 0) {
            resolve(dirHandle(granted[0].grant, '', granted[0].name));
            return;
          }
        } catch (e) { /* fall through and prompt */ }
      }
      var id = 'r' + (++seq);
      pending[id] = {
        resolve: function (r) { resolve(dirHandle(r.grant, '', r.name)); },
        reject: reject
      };
      N.requestDirectory(id);
    });
  }

  if (window.showDirectoryPicker) window.__nativeShowDirectoryPicker = window.showDirectoryPicker;
  window.showDirectoryPicker = function () { return pick(false); };

  window.pwaLibrary = window.pwaLibrary || {};
  window.pwaLibrary.files = {
    /** Always prompts, even without a user gesture. */
    pickFolder: function () { return pick(true); },
    /** Folders already granted to this app, without prompting. */
    folders: function () {
      try {
        return Promise.resolve(call(N.listGrants()).grants.map(function (g) {
          return dirHandle(g.grant, '', g.name);
        }));
      } catch (e) { return Promise.reject(e); }
    },
    /**
     * Rebuilds a handle from the plain object left after a structured clone.
     * Storing a handle in IndexedDB drops its methods, since these are ordinary
     * objects rather than the browser's own handle types.
     */
    adopt: function (obj) {
      var d = obj && obj.__pwalib;
      if (!d) return null;
      var name = obj.name || '';
      return d.kind === 'directory'
        ? dirHandle(d.grant, d.path, name)
        : fileHandle(d.grant, d.path, name);
    }
  };
})();
"""
}
