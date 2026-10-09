/*
 * Browser Bridge — background script: the engine's observation channel.
 *
 * GeckoView exposes no request interception to embedders (no shouldInterceptRequest, no
 * evaluateJavascript), but Gecko does grant its extensions two APIs that together see everything
 * the app needs to observe:
 *
 *   webRequest  — every request on the wire: URL, method, resource type, the FULL request and
 *                 response header sets, the status of every response (not just 4xx/5xx), every
 *                 redirect hop, timing, network-level errors, and — with webRequestBlocking — a
 *                 streamed preview of text-like response bodies via filterResponseData.
 *   cookies     — the engine's whole cookie store, with every attribute (domain, path, Secure,
 *                 HttpOnly, expiry, SameSite), which WebView's CookieManager never exposed.
 *
 * This script assembles one record per request and pushes it to the app over native messaging.
 * The app applies its own masking policy at capture time (the values cross the boundary raw), so
 * "Reveal sensitive values" behaves identically under both engines.
 *
 * The listeners are attached only while the app says capture is on: the script polls the app for
 * its capture settings (a pull, because native code cannot push to an extension) and adds or
 * removes the listeners when the answer changes. A blocking listener is kept only for the
 * text-like resource types, so images, media and WebSockets never pay for body streaming.
 *
 * Content scripts keep talking to the app directly (runtime.connectNative, "nativeMessagingFrom
 * Content"); this background script does not sit on that path.
 */
(function () {
  "use strict";

  const NATIVE_APP = "browserbridge-net";

  // How often the capture settings are re-read from the app. A settings change is picked up
  // within this interval; it is a debug tool, so a couple of seconds of lag is fine.
  const SETTINGS_POLL_MS = 2000;

  // Resource types whose bodies are worth previewing as text. Everything else (images, media,
  // fonts, WebSockets) would only produce binary noise, and streams that never end would keep the
  // filter open for nothing.
  const BODY_PREVIEW_TYPES = ["main_frame", "sub_frame", "xmlhttprequest", "script", "stylesheet"];

  // Matches the app's InspectorLimits.MAX_ENGINE_BODY_PREVIEW_CHARS.
  const BODY_PREVIEW_MAX_CHARS = 8192;

  // Message-size caps: the app truncates header values again at store time, these only keep the
  // native messages small.
  const HEADERS_MAX = 40;
  const HEADER_VALUE_MAX_CHARS = 1500;
  const URL_MAX_CHARS = 4000;
  const PENDING_MAX = 512;
  const REDIRECT_HISTORY_MAX = 64;
  const COOKIE_DUMP_MAX = 1000;

  let captureEnabled = false;
  let responsePreviewsEnabled = false;
  let passiveListenersAttached = false;
  let bodyListenerAttached = false;

  // requestId -> partially assembled record
  const pending = new Map();
  // url -> the url it was redirected from (to mark the next hop of a chain)
  const redirectHistory = new Map();
  // requestIds whose body filter is still streaming: their record is finished by the filter's
  // onstop, not by onCompleted, so the preview is never dropped by a race between the two.
  const filtering = new Set();
  const completedWhileFiltering = new Set();

  // ------------------------------------------------------------------ transport

  function send(message) {
    try {
      const result = browser.runtime.sendNativeMessage(NATIVE_APP, message);
      if (result && typeof result.catch === "function") result.catch(function () { /* app not listening */ });
    } catch (_) {
      /* the app is gone; nothing to do */
    }
  }

  // ------------------------------------------------------------------ capture settings

  function pullSettings() {
    let reply;
    try {
      reply = browser.runtime.sendNativeMessage(NATIVE_APP, { type: "get-capture-settings" });
    } catch (_) {
      return;
    }
    Promise.resolve(reply).then(
      function (settings) {
        if (!settings || typeof settings !== "object") return;
        applySettings(settings);
      },
      function () { /* app not listening yet */ }
    );
  }

  function applySettings(settings) {
    const enabled = settings.enabled === true;
    const previews = settings.responsePreviews === true;
    if (enabled !== captureEnabled) {
      captureEnabled = enabled;
      updatePassiveListeners();
    }
    if (previews !== responsePreviewsEnabled) {
      responsePreviewsEnabled = previews;
      updateBodyListener();
    }
    reportStatus();
  }

  // ------------------------------------------------------------------ self-diagnostics

  // The app cannot see why capture is or is not working, so the extension says so itself:
  // which APIs and permissions it actually has, which listeners are attached, and the manifest
  // version actually running (an outdated installed copy would explain "nothing changed" after
  // an upgrade). The app prints this in the inspector's export under CAPTURE SOURCE.
  let lastListenerError = null;

  function probePermissions() {
    const checks = {};
    const wanted = [
      ["webRequest", { permissions: ["webRequest"] }],
      ["webRequestBlocking", { permissions: ["webRequestBlocking"] }],
      ["cookies", { permissions: ["cookies"] }],
      ["allUrls", { origins: ["<all_urls>"] }]
    ];
    if (!browser.permissions || typeof browser.permissions.contains !== "function") {
      wanted.forEach(function (entry) { checks[entry[0]] = null; });
      return Promise.resolve(checks);
    }
    return Promise.all(
      wanted.map(function (entry) {
        return Promise.resolve(browser.permissions.contains(entry[1]))
          .then(function (ok) { checks[entry[0]] = ok === true; })
          .catch(function () { checks[entry[0]] = false; });
      })
    ).then(function () { return checks; });
  }

  function reportStatus() {
    let manifestVersion = "";
    try {
      manifestVersion = String(browser.runtime.getManifest().version || "");
    } catch (_) { /* getManifest unavailable */ }
    probePermissions().then(function (permissions) {
      send({
        type: "capture-status",
        manifestVersion: manifestVersion,
        webRequestApi: typeof browser.webRequest === "object" && browser.webRequest !== null,
        cookiesApi: typeof browser.cookies === "object" && browser.cookies !== null,
        permissions: permissions,
        captureEnabled: captureEnabled,
        responsePreviewsEnabled: responsePreviewsEnabled,
        passiveListenersAttached: passiveListenersAttached,
        bodyListenerAttached: bodyListenerAttached,
        pendingRequests: pending.size,
        listenerError: lastListenerError
      });
    });
  }

  // ------------------------------------------------------------------ record assembly

  function headersToObject(headers) {
    const result = {};
    if (!Array.isArray(headers)) return result;
    let count = 0;
    for (let index = 0; index < headers.length && count < HEADERS_MAX; index += 1) {
      const header = headers[index];
      if (!header || typeof header.name !== "string") continue;
      const name = header.name;
      let value = typeof header.value === "string" ? header.value : "";
      if (value.length > HEADER_VALUE_MAX_CHARS) value = value.slice(0, HEADER_VALUE_MAX_CHARS);
      if (Object.prototype.hasOwnProperty.call(result, name)) {
        // Repeated headers (several Set-Cookie lines, for example) are joined the way HTTP joins
        // them; the app's cookie store is the authoritative source for cookies.
        result[name] = result[name] + ", " + value;
      } else {
        result[name] = value;
        count += 1;
      }
    }
    return result;
  }

  function clipUrl(url) {
    if (typeof url !== "string") return "";
    return url.length > URL_MAX_CHARS ? url.slice(0, URL_MAX_CHARS) : url;
  }

  function recordFor(details) {
    let record = pending.get(details.requestId);
    if (!record) {
      record = {
        requestId: String(details.requestId),
        url: clipUrl(details.url),
        method: typeof details.method === "string" && details.method ? details.method : "GET",
        documentUrl: clipUrl(details.documentUrl || details.originUrl || ""),
        resourceType: typeof details.type === "string" ? details.type : "other",
        isMainFrame: details.type === "main_frame",
        redirectedFrom: null,
        redirectedTo: null,
        requestHeaders: {},
        statusCode: null,
        responseHeaders: {},
        startTime: typeof details.timeStamp === "number" ? details.timeStamp : 0,
        endTime: null,
        error: null,
        bodyPreview: null,
        bodyTruncated: false
      };
      const previous = redirectHistory.get(record.url);
      if (previous) record.redirectedFrom = previous;
      pending.set(details.requestId, record);
      if (pending.size > PENDING_MAX) {
        // A request that never completes must not grow this map without bound.
        const oldest = pending.keys().next();
        if (!oldest.done) pending.delete(oldest.value);
      }
    }
    return record;
  }

  function rememberRedirect(fromUrl, toUrl) {
    redirectHistory.set(toUrl, fromUrl);
    if (redirectHistory.size > REDIRECT_HISTORY_MAX) {
      const oldest = redirectHistory.keys().next();
      if (!oldest.done) redirectHistory.delete(oldest.value);
    }
  }

  function finishRecord(record, error) {
    if (error) record.error = error;
    send({ type: "network-record", record: record });
    pending.delete(record.requestId);
    filtering.delete(record.requestId);
    completedWhileFiltering.delete(record.requestId);
  }

  // Called when a body filter has finished streaming: attach the preview and, if the request's
  // completion event already arrived, finish the record now.
  function filterDone(requestId) {
    filtering.delete(requestId);
    if (!completedWhileFiltering.has(requestId)) return;
    completedWhileFiltering.delete(requestId);
    const record = pending.get(requestId);
    if (record) finishRecord(record, null);
  }

  // ------------------------------------------------------------------ webRequest listeners

  function onBeforeRequest(details) {
    recordFor(details);
  }

  function onBeforeSendHeaders(details) {
    const record = pending.get(details.requestId);
    if (record) record.requestHeaders = headersToObject(details.requestHeaders);
  }

  function onHeadersReceived(details) {
    const record = pending.get(details.requestId);
    if (!record) return;
    record.statusCode = typeof details.statusCode === "number" ? details.statusCode : null;
    record.responseHeaders = headersToObject(details.responseHeaders);
    if (record.endTime == null && typeof details.timeStamp === "number") {
      record.endTime = details.timeStamp;
    }
  }

  function onBeforeRedirect(details) {
    const record = pending.get(details.requestId);
    if (!record) return;
    record.statusCode = typeof details.statusCode === "number" ? details.statusCode : record.statusCode;
    if (record.endTime == null && typeof details.timeStamp === "number") {
      record.endTime = details.timeStamp;
    }
    if (typeof details.redirectUrl === "string" && details.redirectUrl) {
      record.redirectedTo = clipUrl(details.redirectUrl);
      rememberRedirect(record.url, details.redirectUrl);
    }
  }

  function onCompleted(details) {
    const record = pending.get(details.requestId);
    if (!record) return;
    if (record.statusCode == null && typeof details.statusCode === "number") {
      record.statusCode = details.statusCode;
    }
    if (record.endTime == null && typeof details.timeStamp === "number") {
      record.endTime = details.timeStamp;
    }
    if (filtering.has(details.requestId)) {
      // The body filter is still streaming; it finishes the record when its onstop fires, so the
      // preview is not lost to the race between the two events.
      completedWhileFiltering.add(details.requestId);
    } else {
      finishRecord(record, null);
    }
  }

  function onErrorOccurred(details) {
    const record = pending.get(details.requestId);
    if (!record) return;
    if (record.endTime == null && typeof details.timeStamp === "number") {
      record.endTime = details.timeStamp;
    }
    finishRecord(record, typeof details.error === "string" && details.error ? details.error : "unknown error");
  }

  // Blocking listener, text-like types only: streams a capped preview of the body while passing
  // every byte through unchanged, so the page is never delayed or altered by the capture.
  function onHeadersReceivedBlocking(details) {
    if (!responsePreviewsEnabled) return;
    let filter;
    try {
      filter = browser.webRequest.filterResponseData(details.requestId);
    } catch (_) {
      return;
    }
    filtering.add(details.requestId);
    const decoder = new TextDecoder("utf-8");
    let text = "";
    let truncated = false;
    filter.ondata = function (event) {
      if (!truncated) {
        text += decoder.decode(event.data, { stream: true });
        if (text.length > BODY_PREVIEW_MAX_CHARS) {
          text = text.slice(0, BODY_PREVIEW_MAX_CHARS);
          truncated = true;
        }
      }
      try {
        filter.write(event.data);
      } catch (_) {
        /* the stream is gone; nothing to pass through to */
      }
    };
    filter.onstop = function () {
      const record = pending.get(details.requestId);
      if (record) {
        record.bodyPreview = text;
        record.bodyTruncated = truncated;
      }
      try {
        filter.close();
      } catch (_) { /* already closed */ }
      filterDone(details.requestId);
    };
    filter.onerror = function () {
      try {
        filter.close();
      } catch (_) { /* already closed */ }
      filterDone(details.requestId);
    };
  }

  function updatePassiveListeners() {
    if (captureEnabled === passiveListenersAttached) return;
    const filter = { urls: ["<all_urls>"] };
    try {
      if (captureEnabled) {
        browser.webRequest.onBeforeRequest.addListener(onBeforeRequest, filter);
        // "requestHeaders" in extraInfoSpec is what makes details.requestHeaders populated at all;
        // without it the listener fires but the headers are simply absent.
        browser.webRequest.onBeforeSendHeaders.addListener(onBeforeSendHeaders, filter, ["requestHeaders"]);
        browser.webRequest.onHeadersReceived.addListener(onHeadersReceived, filter, ["responseHeaders"]);
        browser.webRequest.onBeforeRedirect.addListener(onBeforeRedirect, filter, ["responseHeaders"]);
        browser.webRequest.onCompleted.addListener(onCompleted, filter);
        browser.webRequest.onErrorOccurred.addListener(onErrorOccurred, filter);
      } else {
        browser.webRequest.onBeforeRequest.removeListener(onBeforeRequest);
        browser.webRequest.onBeforeSendHeaders.removeListener(onBeforeSendHeaders);
        browser.webRequest.onHeadersReceived.removeListener(onHeadersReceived);
        browser.webRequest.onBeforeRedirect.removeListener(onBeforeRedirect);
        browser.webRequest.onCompleted.removeListener(onCompleted);
        browser.webRequest.onErrorOccurred.removeListener(onErrorOccurred);
        pending.clear();
      }
      lastListenerError = null;
    } catch (error) {
      // Without webRequest (permission not granted, API missing) capture cannot work; say so
      // instead of failing silently on every settings poll.
      lastListenerError = String(error && error.message ? error.message : error);
    }
    passiveListenersAttached = captureEnabled;
  }

  function updateBodyListener() {
    if (responsePreviewsEnabled === bodyListenerAttached) return;
    const filter = { urls: ["<all_urls>"], types: BODY_PREVIEW_TYPES };
    try {
      if (responsePreviewsEnabled) {
        browser.webRequest.onHeadersReceived.addListener(
          onHeadersReceivedBlocking,
          filter,
          ["blocking", "responseHeaders"]
        );
      } else {
        browser.webRequest.onHeadersReceived.removeListener(onHeadersReceivedBlocking);
      }
      lastListenerError = null;
    } catch (error) {
      lastListenerError = String(error && error.message ? error.message : error);
    }
    bodyListenerAttached = responsePreviewsEnabled;
  }

  // ------------------------------------------------------------------ cookies

  function toWireCookie(cookie) {
    return {
      name: String(cookie.name || ""),
      value: typeof cookie.value === "string" ? cookie.value : "",
      domain: String(cookie.domain || ""),
      path: String(cookie.path || "/"),
      secure: cookie.secure === true,
      httpOnly: cookie.httpOnly === true,
      session: cookie.session === true,
      expirationDate: typeof cookie.expirationDate === "number" ? cookie.expirationDate : null,
      sameSite: typeof cookie.sameSite === "string" ? cookie.sameSite : null,
      hostOnly: cookie.hostOnly === true
    };
  }

  function pushCookieDump() {
    let cookies;
    try {
      cookies = browser.cookies.getAll({});
    } catch (_) {
      return;
    }
    Promise.resolve(cookies).then(
      function (list) {
        if (!Array.isArray(list)) return;
        send({
          type: "cookies-dump",
          cookies: list.slice(0, COOKIE_DUMP_MAX).map(toWireCookie)
        });
      },
      function () { /* cookies API unavailable */ }
    );
  }

  try {
    browser.cookies.onChanged.addListener(function (changeInfo) {
      if (!changeInfo || !changeInfo.cookie) return;
      send({
        type: changeInfo.removed ? "cookie-removed" : "cookie-changed",
        cookie: toWireCookie(changeInfo.cookie)
      });
    });
  } catch (_) {
    /* cookies API unavailable */
  }

  // ------------------------------------------------------------------ start

  pullSettings();
  setInterval(pullSettings, SETTINGS_POLL_MS);
  pushCookieDump();
  reportStatus();
})();
