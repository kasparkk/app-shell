/* app-shell: ergaenzt in der WebView, was Chrome kann und die WebView nicht:
   Downloads (blob:/data:/download-Attribut), navigator.share, window.print. */
(function () {
  if (window.__appShell) return;
  var A = window.AndroidShell;
  if (!A) return;
  window.__appShell = true;
  window.isAndroidShell = true;

  function b64(blob) {
    return new Promise(function (res, rej) {
      var r = new FileReader();
      r.onload = function () { var s = String(r.result); res(s.slice(s.indexOf(',') + 1)); };
      r.onerror = function () { rej(r.error); };
      r.readAsDataURL(blob);
    });
  }

  function extOf(type) {
    var t = (type || '').split(';')[0].trim();
    var map = { 'application/pdf': 'pdf', 'application/json': 'json', 'text/plain': 'txt', 'text/csv': 'csv',
      'image/png': 'png', 'image/jpeg': 'jpg', 'image/svg+xml': 'svg', 'text/html': 'html',
      'application/zip': 'zip', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx' };
    return map[t] || (t.split('/')[1] || 'bin');
  }

  function nameFor(url, blob, dl) {
    if (dl) return dl;
    var n = '';
    try {
      if (!/^(blob|data):/i.test(url)) n = decodeURIComponent(new URL(url, location.href).pathname.split('/').pop() || '');
    } catch (e) {}
    if (!n) n = 'download-' + new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-') + '.' + extOf(blob && blob.type);
    return n;
  }

  function save(url, dl, open) {
    url = String(url);
    return fetch(url).then(function (r) { return r.blob(); }).then(function (blob) {
      return b64(blob).then(function (d) {
        A.saveFile(nameFor(url, blob, dl), blob.type || 'application/octet-stream', d, !!open);
      });
    }).catch(function (e) {
      if (/^https?:/i.test(url)) A.downloadUrl(url, dl || '');
      else A.toast('Download fehlgeschlagen: ' + (e && e.message || e));
    });
  }
  window.__shellSave = save;

  /* Viele Apps rufen revokeObjectURL direkt nach dem Klick auf; wir lesen asynchron. */
  var revoke = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = function (u) { setTimeout(function () { revoke(u); }, 60000); };

  function isDownload(a) {
    if (!a || !a.href) return false;
    return a.hasAttribute('download') || /^(blob|data):/i.test(a.href);
  }
  function handle(a) {
    if (!isDownload(a)) return false;
    save(a.href, a.getAttribute('download') || '', false);
    return true;
  }

  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (a && !e.defaultPrevented && isDownload(a)) {
      e.preventDefault();
      handle(a);
    }
  }, true);

  /* Programmatische Downloads mit nicht eingehaengtem <a> (a.click(), FileSaver.js) */
  var aClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (!this.isConnected && handle(this)) return;
    return aClick.call(this);
  };
  var aDispatch = HTMLAnchorElement.prototype.dispatchEvent;
  HTMLAnchorElement.prototype.dispatchEvent = function (ev) {
    if (ev && ev.type === 'click' && !this.isConnected && handle(this)) return false;
    return aDispatch.call(this, ev);
  };

  var wOpen = window.open;
  window.open = function (u) {
    if (u && /^(blob|data):/i.test(String(u))) { save(String(u), '', true); return null; }
    return wOpen.apply(window, arguments);
  };

  window.print = function () {
    try { window.dispatchEvent(new Event('beforeprint')); } catch (e) {}
    A.print(document.title || '');
  };
  window.__shellAfterPrint = function () {
    try { window.dispatchEvent(new Event('afterprint')); } catch (e) {}
  };

  function share(d) {
    d = d || {};
    var files = Array.prototype.slice.call(d.files || []);
    return Promise.all(files.map(function (f) {
      return b64(f).then(function (x) { return { name: f.name || ('datei.' + extOf(f.type)), type: f.type || 'application/octet-stream', data: x }; });
    })).then(function (fs) {
      A.share(JSON.stringify({ title: d.title || '', text: d.text || '', url: d.url || '', files: fs }));
    });
  }
  try {
    Object.defineProperty(navigator, 'share', { value: share, configurable: true, writable: true });
    Object.defineProperty(navigator, 'canShare', { value: function () { return true; }, configurable: true, writable: true });
  } catch (e) {}
})();
