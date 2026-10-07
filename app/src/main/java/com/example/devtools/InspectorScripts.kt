package com.example.devtools

/**
 * The JavaScript this inspector injects into pages, and nothing else.
 *
 * Scope, on purpose: the hooks observe what the page asks for. They do not modify requests or
 * responses, do not forge headers, do not rewrite responses, do not touch cookies and do not try to
 * defeat anything a site does (no anti-bot handling, no CAPTCHA handling, no fingerprinting, no rate
 * limit avoidance). Every hook is installed defensively and falls back to the original function if
 * anything goes wrong, so a page that behaves oddly still behaves normally.
 *
 * What the hooks do:
 *
 *  * wrap `window.fetch`, `XMLHttpRequest.prototype` and `navigator.sendBeacon` to record URL,
 *    method, request headers and (when enabled) the body the *page* passed;
 *  * read response status, status text, headers, `Content-Type`, the final URL (`response.url` /
 *    `xhr.responseURL`, which is how redirects become visible) and the page-side duration;
 *  * optionally read a size-capped preview of text-typed response bodies, from a `Response.clone()`
 *    or `xhr.responseText` — never from anything the app did not already receive;
 *  * report uncaught errors and unhandled rejections as console rows with stack traces.
 *
 * What the hooks never do: touch `document.cookie`, read storage, hook `WebSocket`, patch `crypto`,
 * `navigator` or canvas, or retry/pause/redirect anything.
 *
 * Data is queued in the page and flushed in batches through the
 * `NetworkInspectorBridge.pushBatch` bridge, which is how the per-message cost stays amortised.
 */
object InspectorScripts {

    /** Bridge object name exposed to page JavaScript. */
    const val BRIDGE_NAME = "NetworkInspectorBridge"

    /** Name of the page-side state object, so reinstalling is idempotent. */
    const val STATE_NAME = "browserNetworkInspector"

    /**
     * Builds the install script.
     *
     * @param captureRequestBodies include bodies passed to fetch/XHR/sendBeacon.
     * @param captureResponseBodies include size-capped text previews of responses.
     */
    fun build(captureRequestBodies: Boolean, captureResponseBodies: Boolean): String {
        val bodies = if (captureRequestBodies) "true" else "false"
        val responses = if (captureResponseBodies) "true" else "false"
        return SCRIPT
            .replace("__BODIES__", bodies)
            .replace("__RESPONSES__", responses)
    }

    /**
     * Sets a flag that stops the hooks from doing anything, used when the inspector is switched off.
     * The hooks stay installed (removing them would risk breaking a page mid-flight); they simply
     * become no-ops.
     */
    val DISABLE = """
        (function () {
          var state = window['$STATE_NAME'];
          if (state) { state.disabled = true; state.queue = []; }
        })();
    """.trimIndent()

    /** Re-enables the hooks after [DISABLE]. */
    val ENABLE = """
        (function () {
          var state = window['$STATE_NAME'];
          if (state) { state.disabled = false; }
        })();
    """.trimIndent()

    private val SCRIPT = """
(function () {
  'use strict';

  var FLUSH_DELAY_MS = 25;
  var MAX_QUEUE = 400;
  var MAX_BATCH_RECORDS = 120;
  var MAX_URL = 2000;
  var MAX_HEADER = 800;
  var MAX_HEADERS = 60;
  var MAX_BODY = 2000;
  var MAX_RESPONSE_BYTES = 262144;
  var MAX_STACK = 4000;

  var bridge = window['NetworkInspectorBridge'];
  if (!bridge || typeof bridge.pushBatch !== 'function') return;

  var state = window['browserNetworkInspector'];
  if (!state) {
    state = window['browserNetworkInspector'] = {
      doc: String(Date.now()) + '-' + Math.random().toString(36).slice(2, 10),
      queue: [],
      dropped: 0,
      flushing: false,
      nextId: 1,
      installed: false,
      disabled: false
    };
  }
  state.config = { bodies: __BODIES__, responses: __RESPONSES__ };
  if (state.installed) return;
  state.installed = true;

  function clip(value, max) {
    var text = value === null || value === undefined ? '' : String(value);
    return text.length > max ? text.slice(0, max) : text;
  }

  function now() { return Date.now(); }

  function perfNow() {
    try { return (window.performance && window.performance.now) ? window.performance.now() : null; } catch (e) { return null; }
  }

  function nextId() { return state.nextId++; }

  function record(item) {
    if (state.disabled) return;
    try {
      if (state.queue.length >= MAX_QUEUE) {
        state.queue.shift();
        state.dropped++;
      }
      state.queue.push(item);
      scheduleFlush();
    } catch (e) {}
  }

  function scheduleFlush() {
    if (state.flushing) return;
    state.flushing = true;
    setTimeout(flush, FLUSH_DELAY_MS);
  }

  function flush() {
    state.flushing = false;
    if (state.disabled || !state.queue.length) return;
    var batch = state.queue.splice(0, MAX_BATCH_RECORDS);
    var dropped = state.dropped;
    state.dropped = 0;
    try {
      bridge.pushBatch(JSON.stringify({ doc: state.doc, r: batch, d: dropped }));
    } catch (e) {
      state.dropped = dropped + batch.length;
    }
    if (state.queue.length) scheduleFlush();
  }

  function headerObject(headers) {
    var out = {};
    try {
      if (!headers) return out;
      var count = 0;
      if (typeof headers.forEach === 'function') {
        headers.forEach(function (value, name) {
          if (count >= MAX_HEADERS) return;
          out[clip(name, 200)] = clip(value, MAX_HEADER);
          count++;
        });
        return out;
      }
      if (Object.prototype.toString.call(headers) === '[object Array]') {
        for (var i = 0; i < headers.length && count < MAX_HEADERS; i++) {
          var pair = headers[i];
          if (pair && pair.length >= 2) {
            out[clip(pair[0], 200)] = clip(pair[1], MAX_HEADER);
            count++;
          }
        }
        return out;
      }
      if (typeof headers === 'object') {
        for (var key in headers) {
          if (!Object.prototype.hasOwnProperty.call(headers, key)) continue;
          if (count >= MAX_HEADERS) break;
          out[clip(key, 200)] = clip(headers[key], MAX_HEADER);
          count++;
        }
      }
    } catch (e) {}
    return out;
  }

  function parseRawHeaders(raw) {
    var out = {};
    try {
      if (!raw) return out;
      var lines = String(raw).split(/\r?\n/);
      var count = 0;
      for (var i = 0; i < lines.length && count < MAX_HEADERS; i++) {
        var line = lines[i];
        if (!line) continue;
        var index = line.indexOf(':');
        if (index <= 0) continue;
        out[clip(line.slice(0, index).trim(), 200)] = clip(line.slice(index + 1).trim(), MAX_HEADER);
        count++;
      }
    } catch (e) {}
    return out;
  }

  function describeBody(value) {
    try {
      if (value === null || value === undefined) return null;
      if (typeof value === 'string') {
        return { text: clip(value, MAX_BODY), kind: 'text', length: value.length, truncated: value.length > MAX_BODY };
      }
      if (window.URLSearchParams && value instanceof URLSearchParams) {
        var encoded = value.toString();
        return { text: clip(encoded, MAX_BODY), kind: 'form', length: encoded.length, truncated: encoded.length > MAX_BODY };
      }
      if (window.FormData && value instanceof FormData) {
        var parts = [];
        try {
          value.forEach(function (entryValue, entryName) {
            if (parts.length >= 40) return;
            if (typeof entryValue === 'string') {
              parts.push(entryName + '=' + clip(entryValue, 120));
            } else {
              parts.push(entryName + '=[file ' + clip(entryValue && entryValue.name ? entryValue.name : 'blob', 80) + ']');
            }
          });
        } catch (e) {}
        var joined = parts.join('&');
        return { text: clip(joined, MAX_BODY), kind: 'multipart', length: joined.length, truncated: joined.length > MAX_BODY };
      }
      if (window.Blob && value instanceof Blob) {
        return { text: '[blob body: ' + value.size + ' bytes, type ' + clip(value.type || 'unknown', 80) + ']', kind: 'binary', length: value.size, truncated: false };
      }
      if (window.ArrayBuffer && (value instanceof ArrayBuffer || (window.ArrayBuffer.isView && ArrayBuffer.isView(value)))) {
        var size = value.byteLength || 0;
        return { text: '[binary body: ' + size + ' bytes]', kind: 'binary', length: size, truncated: false };
      }
      if (window.ReadableStream && value instanceof ReadableStream) {
        return { text: '[streaming body: the page consumes this stream, so the inspector cannot read it]', kind: 'stream', length: null, truncated: false };
      }
      return { text: clip(String(value), MAX_BODY), kind: 'unknown', length: null, truncated: false };
    } catch (e) {
      return null;
    }
  }

  function applyBody(item, described) {
    if (!described) return;
    item.b = described.text;
    item.bk = described.kind;
    if (described.length !== null && described.length !== undefined) item.bl = described.length;
    item.bt = described.truncated ? 1 : 0;
  }

  function readCappedText(response, maxChars, done) {
    try {
      if (!response || !response.body || !window.TextDecoder) {
        done(null);
        return;
      }
      var reader = response.body.getReader();
      var decoder = new TextDecoder('utf-8', { fatal: false });
      var text = '';
      var bytes = 0;
      var finished = false;

      function finish(result) {
        if (finished) return;
        finished = true;
        done(result);
      }

      function pump() {
        reader.read().then(function (chunk) {
          if (finished) return;
          if (chunk.done) {
            finish({ text: clip(text, maxChars), truncated: text.length > maxChars });
            return;
          }
          if (chunk.value) {
            bytes += chunk.value.length;
            text += decoder.decode(chunk.value, { stream: true });
          }
          if (text.length >= maxChars || bytes >= MAX_RESPONSE_BYTES) {
            try { reader.cancel(); } catch (e) {}
            finish({ text: clip(text, maxChars), truncated: true });
            return;
          }
          pump();
        }).catch(function () {
          finish(text ? { text: clip(text, maxChars), truncated: true } : null);
        });
      }

      pump();
    } catch (e) {
      done(null);
    }
  }

  // ------------------------------------------------------------------- fetch
  var originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    var patchedFetch = function (input, init) {
      var id = nextId();
      var url = '';
      var method = 'GET';
      var headers = {};
      var start = perfNow();
      try {
        if (typeof input === 'string') {
          url = input;
        } else if (input && typeof input.url === 'string') {
          url = input.url;
        } else if (input && typeof input.toString === 'function') {
          url = input.toString();
        }
        if (init && init.method) method = String(init.method);
        else if (input && input.method) method = String(input.method);
        var headerSource = (init && init.headers) ? init.headers : (input ? input.headers : null);
        headers = headerObject(headerSource);
      } catch (e) {}

      var requestItem = { k: 'req', id: id, url: clip(url, MAX_URL), m: String(method).toUpperCase(), t: now(), h: headers, i: 'fetch' };
      if (state.config.bodies) {
        try { applyBody(requestItem, describeBody(init ? init.body : null)); } catch (e) {}
      }
      record(requestItem);

      var promise;
      try {
        promise = originalFetch.apply(this, arguments);
      } catch (error) {
        record({ k: 'err', id: id, url: clip(url, MAX_URL), m: String(method).toUpperCase(), t: now(), em: clip(error && error.message ? error.message : String(error), 500), stack: clip(error && error.stack, MAX_STACK) });
        throw error;
      }

      if (!promise || typeof promise.then !== 'function') return promise;

      return promise.then(function (response) {
        try { onFetchResponse(id, response, start, url, method); } catch (e) {}
        return response;
      }, function (error) {
        try {
          record({
            k: 'err',
            id: id,
            url: clip(url, MAX_URL),
            m: String(method).toUpperCase(),
            t: now(),
            em: clip(error && error.message ? error.message : String(error), 500),
            stack: clip(error && error.stack, MAX_STACK)
          });
        } catch (e) {}
        throw error;
      });
    };
    try {
      window.fetch = patchedFetch;
    } catch (e) {}
  }

  function onFetchResponse(id, response, start, url, method) {
    var duration = null;
    var end = perfNow();
    if (start !== null && end !== null) duration = Math.max(0, Math.round(end - start));

    var headers = {};
    var contentType = null;
    try { headers = headerObject(response.headers); } catch (e) {}
    try { contentType = response.headers && response.headers.get ? response.headers.get('content-type') : null; } catch (e) {}

    var item = {
      k: 'res',
      id: id,
      url: clip(url, MAX_URL),
      m: String(method).toUpperCase(),
      t: now(),
      rh: headers,
      ct: contentType ? clip(contentType, 200) : null,
      fu: response.url ? clip(response.url, MAX_URL) : null,
      du: duration
    };
    if (typeof response.status === 'number' && response.status > 0) item.st = response.status;
    if (response.statusText) item.sx = clip(response.statusText, 120);

    if (state.config.responses) {
      try {
        var type = contentType ? String(contentType) : '';
        var isTextLike = /json|text|javascript|xml|html|form-urlencoded/i.test(type);
        var declaredLength = parseInt(headers['content-length'], 10);
        var sizeOk = !isFinite(declaredLength) || declaredLength <= MAX_RESPONSE_BYTES;
        if (isTextLike && sizeOk && typeof response.clone === 'function') {
          readCappedText(response.clone(), MAX_BODY, function (result) {
            if (result) {
              item.rb = result.text;
              item.rbt = result.truncated ? 1 : 0;
            }
            record(item);
          });
          return;
        }
      } catch (e) {}
    }
    record(item);
  }

  // ------------------------------------------------------------------- XMLHttpRequest
  var XHR = window.XMLHttpRequest;
  if (XHR && XHR.prototype) {
    var originalOpen = XHR.prototype.open;
    var originalSend = XHR.prototype.send;
    var originalSetRequestHeader = XHR.prototype.setRequestHeader;

    XHR.prototype.open = function (method, url) {
      try {
        this['__niState'] = {
          id: nextId(),
          method: String(method || 'GET').toUpperCase(),
          url: String(url === undefined || url === null ? '' : url),
          headers: {},
          start: null,
          listening: false
        };
      } catch (e) {}
      return originalOpen.apply(this, arguments);
    };

    XHR.prototype.setRequestHeader = function (name, value) {
      try {
        var info = this['__niState'];
        if (info && info.headers) info.headers[clip(name, 200)] = clip(value, MAX_HEADER);
      } catch (e) {}
      return originalSetRequestHeader.apply(this, arguments);
    };

    XHR.prototype.send = function (body) {
      var self = this;
      try {
        var info = self['__niState'];
        if (!info) {
          info = self['__niState'] = { id: nextId(), method: 'GET', url: '', headers: {}, start: null, listening: false };
        }
        info.start = perfNow();
        var requestItem = { k: 'req', id: info.id, url: clip(info.url, MAX_URL), m: info.method, t: now(), h: info.headers, i: 'xhr' };
        if (state.config.bodies) {
          try { applyBody(requestItem, describeBody(body)); } catch (e) {}
        }
        record(requestItem);
        if (!info.listening) {
          info.listening = true;
          self.addEventListener('loadend', function () {
            try { onXhrFinished(self, info); } catch (e) {}
          });
        }
      } catch (e) {}
      return originalSend.apply(this, arguments);
    };
  }

  function onXhrFinished(xhr, info) {
    var duration = null;
    if (info && info.start !== null) {
      var end = perfNow();
      if (end !== null) duration = Math.max(0, Math.round(end - info.start));
    }

    var headers = {};
    try { headers = parseRawHeaders(xhr.getAllResponseHeaders ? xhr.getAllResponseHeaders() : ''); } catch (e) {}

    var status = 0;
    try { status = xhr.status || 0; } catch (e) {}

    var finalUrl = null;
    try { if (xhr.responseURL) finalUrl = clip(xhr.responseURL, MAX_URL); } catch (e) {}

    var item = {
      k: 'res',
      id: info ? info.id : -1,
      url: finalUrl ? finalUrl : clip(info ? info.url : '', MAX_URL),
      m: info ? info.method : 'GET',
      t: now(),
      rh: headers,
      ct: null,
      fu: finalUrl,
      du: duration
    };
    if (status > 0) item.st = status;
    try { if (xhr.statusText) item.sx = clip(xhr.statusText, 120); } catch (e) {}
    try {
      var contentType = xhr.getResponseHeader ? xhr.getResponseHeader('content-type') : null;
      if (contentType) item.ct = clip(contentType, 200);
    } catch (e) {}

    if (state.config.responses && status > 0) {
      try {
        var type = item.ct ? String(item.ct) : '';
        var responseType = xhr.responseType === undefined ? '' : xhr.responseType;
        if ((responseType === '' || responseType === 'text') && /json|text|javascript|xml|html|form-urlencoded/i.test(type)) {
          if (typeof xhr.responseText === 'string') {
            var text = xhr.responseText;
            item.rb = clip(text, MAX_BODY);
            item.rbt = text.length > MAX_BODY ? 1 : 0;
          }
        }
      } catch (e) {}
    }

    record(item);
  }

  // ------------------------------------------------------------------- navigator.sendBeacon
  if (navigator.sendBeacon) {
    var originalBeacon = navigator.sendBeacon;
    try {
      navigator.sendBeacon = function (url, data) {
        try {
          var item = { k: 'req', id: nextId(), url: clip(url, MAX_URL), m: 'POST', t: now(), h: {}, i: 'beacon' };
          if (state.config.bodies) {
            try { applyBody(item, describeBody(data)); } catch (e) {}
          }
          record(item);
        } catch (e) {}
        return originalBeacon.apply(navigator, arguments);
      };
    } catch (e) {}
  }

  // ------------------------------------------------------------------- uncaught errors
  function onWindowError(event) {
    try {
      if (!event) return;
      if (event.target && event.target !== window && event.target.tagName) return; // resource load error
      var message = event.message || (event.error && event.error.message) || 'Uncaught error';
      var stack = (event.error && event.error.stack) ? event.error.stack : null;
      if (!stack && event.filename) {
        stack = event.filename + ':' + (event.lineno || 0) + ':' + (event.colno || 0);
      }
      record({
        k: 'cerr',
        em: clip(message, 500),
        stack: stack ? clip(stack, MAX_STACK) : null,
        url: clip(event.filename || '', MAX_URL),
        t: now()
      });
    } catch (e) {}
  }

  function onUnhandledRejection(event) {
    try {
      var reason = event ? event.reason : null;
      var message = reason && reason.message ? reason.message : String(reason === undefined ? 'Unhandled promise rejection' : reason);
      var stack = reason && reason.stack ? reason.stack : null;
      record({
        k: 'cerr',
        em: clip('Unhandled promise rejection: ' + message, 500),
        stack: stack ? clip(stack, MAX_STACK) : null,
        url: '',
        t: now()
      });
    } catch (e) {}
  }

  try {
    window.addEventListener('error', onWindowError, true);
    window.addEventListener('unhandledrejection', onUnhandledRejection, true);
  } catch (e) {}

  scheduleFlush();
})();
    """.trimIndent()
}
