"use strict";

// This is a built-in extension, installed only inside this browser's GeckoRuntime.
// It never sends captured traffic to a remote service. Capture is disabled by default.
const NATIVE_APP = "browser";
const REQUEST_BODY_LIMIT = 1024 * 1024;
const RESPONSE_BODY_LIMIT = 2 * 1024 * 1024;
const TOTAL_BODY_LIMIT = 16 * 1024 * 1024;
const BODY_CHUNK_SIZE = 12 * 1024; // divisible by three so base64 chunks concatenate safely
const FILTER = { urls: ["<all_urls>"] };

let nativePort = null;
let isRecording = false;
let remainingBodyBudget = TOTAL_BODY_LIMIT;
let nextCaptureNumber = 1;
let reconnectTimer = null;
const requestsById = new Map();

function sendToApp(message) {
  if (!nativePort) return;
  try {
    nativePort.postMessage(message);
  } catch (_) {
    // Do not interfere with page loading if the inspector bridge is unavailable.
  }
}

function connectToApp() {
  reconnectTimer = null;
  try {
    const port = browser.runtime.connectNative(NATIVE_APP);
    nativePort = port;
    port.onMessage.addListener(onAppMessage);
    port.onDisconnect.addListener(() => {
      if (nativePort === port) nativePort = null;
      isRecording = false;
      requestsById.clear();
      scheduleReconnect();
    });
    sendToApp({ type: "ready", protocolVersion: 1 });
  } catch (_) {
    nativePort = null;
    scheduleReconnect();
  }
}

function scheduleReconnect() {
  if (reconnectTimer !== null) return;
  reconnectTimer = setTimeout(connectToApp, 1000);
}

function onAppMessage(message) {
  if (!message || typeof message !== "object") return;
  if (message.type === "setRecording") {
    isRecording = message.enabled === true;
    if (Number.isFinite(message.bodyBudgetBytes)) {
      remainingBodyBudget = Math.max(0, Math.min(TOTAL_BODY_LIMIT, Math.floor(message.bodyBudgetBytes)));
    }
    sendToApp({ type: "captureState", enabled: isRecording });
  }
}

function asBytes(value) {
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  if (Array.isArray(value)) return new Uint8Array(value);
  return null;
}

function bytesToBase64(bytes) {
  let binary = "";
  for (let index = 0; index < bytes.length; index += 1) {
    binary += String.fromCharCode(bytes[index]);
  }
  return btoa(binary);
}

function emitBodyBytes(request, direction, value, limit) {
  const bytes = asBytes(value);
  if (!bytes || bytes.length === 0) return;

  const countKey = direction === "request" ? "requestBodyBytes" : "responseBodyBytes";
  const truncatedKey = direction === "request" ? "requestBodyTruncated" : "responseBodyTruncated";
  const remainingForRequest = Math.max(0, limit - request[countKey]);
  const capturedLength = Math.min(bytes.length, remainingForRequest, remainingBodyBudget);
  remainingBodyBudget -= capturedLength;

  for (let offset = 0; offset < capturedLength; offset += BODY_CHUNK_SIZE) {
    const end = Math.min(capturedLength, offset + BODY_CHUNK_SIZE);
    const chunk = bytes.subarray(offset, end);
    sendToApp({
      type: "bodyChunk",
      captureId: request.captureId,
      direction,
      data: bytesToBase64(chunk)
    });
  }

  request[countKey] += capturedLength;
  if (capturedLength < bytes.length) request[truncatedKey] = true;
}

function emitRequestBody(details, request) {
  const body = details.requestBody;
  if (!body) {
    sendToApp({
      type: "requestBodyMeta",
      captureId: request.captureId,
      available: false,
      format: "not-provided"
    });
    return;
  }

  let format = "unavailable";
  let note = body.error ? String(body.error) : "";
  let filePartFound = false;

  if (Array.isArray(body.raw)) {
    format = "raw-bytes";
    for (const item of body.raw) {
      if (item && item.bytes !== undefined) {
        emitBodyBytes(request, "request", item.bytes, REQUEST_BODY_LIMIT);
      } else if (item && item.file) {
        // Gecko can expose an upload's local path rather than its bytes. Never export
        // that device path; make the missing file data explicit instead.
        filePartFound = true;
      }
    }
  } else if (body.formData && typeof body.formData === "object") {
    format = "form-data-fields";
    note = note || "Gecko exposed parsed form fields; this reconstructed representation may not match multipart wire bytes.";
    const fields = new URLSearchParams();
    for (const [name, values] of Object.entries(body.formData)) {
      for (const value of Array.isArray(values) ? values : [values]) {
        fields.append(name, String(value));
      }
    }
    emitBodyBytes(request, "request", new TextEncoder().encode(fields.toString()), REQUEST_BODY_LIMIT);
  }

  if (filePartFound) {
    note = note ? `${note}; upload file bytes are not exposed by Gecko` : "Upload file bytes are not exposed by Gecko.";
  }
  request.requestBodyNote = note;
  request.requestBodyFormat = format;

  sendToApp({
    type: "requestBodyMeta",
    captureId: request.captureId,
    available: true,
    format,
    note,
    truncated: request.requestBodyTruncated,
    bytesCaptured: request.requestBodyBytes
  });
}

function serializeHeaders(headers) {
  return (headers || []).map((header) => {
    const name = String(header && header.name ? header.name : "");
    if (header && typeof header.value === "string") return { name, value: header.value };
    const bytes = header && header.binaryValue !== undefined ? asBytes(header.binaryValue) : null;
    if (bytes) return { name, binaryValue: bytesToBase64(bytes) };
    return { name, value: "" };
  });
}

function headerValue(headers, targetName) {
  const match = (headers || []).find((header) =>
    String(header && header.name || "").toLowerCase() === targetName.toLowerCase()
  );
  return match && typeof match.value === "string" ? match.value : null;
}

function installResponseFilter(details, request) {
  if (!/^https?:/i.test(details.url) || !browser.webRequest.filterResponseData) {
    request.responseFilterDone = true;
    request.responseBodyNote = "Response body filtering is unavailable for this request type.";
    return;
  }

  try {
    const filter = browser.webRequest.filterResponseData(details.requestId);
    request.responseFilter = filter;
    request.responseFilterDone = false;
    filter.ondata = (event) => {
      const view = asBytes(event.data);
      const capturedCopy = view ? new Uint8Array(view) : null;
      // Always pass the original bytes through unchanged. Logging is observational only.
      try {
        filter.write(event.data);
      } catch (_) {
        // If Gecko has already closed the stream, there is nothing more to forward.
      }
      if (capturedCopy) emitBodyBytes(request, "response", capturedCopy, RESPONSE_BODY_LIMIT);
    };
    filter.onstop = () => {
      request.responseFilterDone = true;
      try { filter.close(); } catch (_) {}
      finishIfReady(request);
    };
    filter.onerror = () => {
      request.responseFilterDone = true;
      request.responseBodyNote = "Gecko stopped exposing the response stream.";
      try { filter.disconnect(); } catch (_) {}
      finishIfReady(request);
    };
  } catch (_) {
    request.responseFilterDone = true;
    request.responseBodyNote = "Gecko could not attach a response-body filter.";
  }
}

function onBeforeRequest(details) {
  if (!isRecording) return {};
  const requestUrl = String(details.url || "");
  if (!/^(https?|wss?):/i.test(requestUrl)) return {};

  const requestId = String(details.requestId || "");
  if (!requestId) return {};

  const startedAt = Math.round(Number(details.timeStamp) || Date.now());
  const previous = requestsById.get(requestId);
  if (previous) {
    // Redirect chains can reuse a Gecko request ID. Close the prior hop before indexing the next.
    previous.finished = true;
    previous.finishedAt = startedAt;
    previous.durationMs = Math.max(0, startedAt - previous.startedAt);
    previous.redirectUrl = requestUrl;
    if (previous.responseFilter && !previous.responseFilterDone) {
      previous.responseFilterDone = true;
      previous.responseBodyTruncated = true;
      previous.responseBodyNote = "Response stream ended at a redirect; any remaining bytes were not captured.";
      try { previous.responseFilter.disconnect(); } catch (_) {}
    }
    finishIfReady(previous);
  }

  const captureId = `${requestId}:${startedAt}:${nextCaptureNumber++}`;
  const request = {
    requestId,
    captureId,
    startedAt,
    redirectUrl: null,
    requestBodyBytes: 0,
    responseBodyBytes: 0,
    requestBodyTruncated: false,
    responseBodyTruncated: false,
    requestBodyNote: "",
    responseBodyNote: "",
    finished: false,
    responseFilterDone: true,
    responseFilter: null
  };
  requestsById.set(requestId, request);

  sendToApp({
    type: "requestStarted",
    captureId,
    requestId,
    url: String(details.url || ""),
    method: String(details.method || "GET"),
    resourceType: String(details.type || "other"),
    tabId: Number.isInteger(details.tabId) && details.tabId >= 0 ? details.tabId : null,
    frameId: Number.isInteger(details.frameId) && details.frameId >= 0 ? details.frameId : null,
    startedAt: request.startedAt,
    initiator: details.originUrl || details.documentUrl || details.initiator || null,
    requestBodyAvailable: !!details.requestBody
  });

  emitRequestBody(details, request);
  installResponseFilter(details, request);
  return {};
}

function onSendHeaders(details) {
  const request = requestsById.get(String(details.requestId || ""));
  if (!request) return;
  sendToApp({
    type: "requestHeaders",
    captureId: request.captureId,
    headers: serializeHeaders(details.requestHeaders)
  });
}

function onHeadersReceived(details) {
  const request = requestsById.get(String(details.requestId || ""));
  if (!request) return;
  const headers = serializeHeaders(details.responseHeaders);
  sendToApp({
    type: "responseHeaders",
    captureId: request.captureId,
    statusCode: Number.isFinite(details.statusCode) ? details.statusCode : null,
    statusLine: typeof details.statusLine === "string" ? details.statusLine : "",
    headers,
    mimeType: headerValue(headers, "content-type")
  });
}

function finishIfReady(request) {
  if (!request.finished || !request.responseFilterDone || request.completionSent) return;
  request.completionSent = true;
  sendToApp({
    type: "requestFinished",
    captureId: request.captureId,
    finishedAt: request.finishedAt,
    durationMs: request.durationMs,
    fromCache: request.fromCache,
    redirectUrl: request.redirectUrl,
    error: request.error,
    requestBodyBytes: request.requestBodyBytes,
    responseBodyBytes: request.responseBodyBytes,
    requestBodyTruncated: request.requestBodyTruncated,
    responseBodyTruncated: request.responseBodyTruncated,
    requestBodyNote: request.requestBodyNote,
    responseBodyNote: request.responseBodyNote
  });
  requestsById.delete(request.requestId);
}

function onCompleted(details) {
  const request = requestsById.get(String(details.requestId || ""));
  if (!request) return;
  request.finished = true;
  request.finishedAt = Math.round(Number(details.timeStamp) || Date.now());
  request.durationMs = Math.max(0, request.finishedAt - request.startedAt);
  request.fromCache = details.fromCache === true;
  finishIfReady(request);
}

function onErrorOccurred(details) {
  const request = requestsById.get(String(details.requestId || ""));
  if (!request) return;
  request.finished = true;
  request.finishedAt = Math.round(Number(details.timeStamp) || Date.now());
  request.durationMs = Math.max(0, request.finishedAt - request.startedAt);
  request.error = String(details.error || "Network request failed");
  if (request.responseFilter && !request.responseFilterDone) {
    request.responseFilterDone = true;
    try { request.responseFilter.disconnect(); } catch (_) {}
  }
  finishIfReady(request);
}

try {
  browser.webRequest.onBeforeRequest.addListener(
    onBeforeRequest,
    FILTER,
    ["blocking", "requestBody"]
  );
  browser.webRequest.onSendHeaders.addListener(
    onSendHeaders,
    FILTER,
    ["requestHeaders"]
  );
  browser.webRequest.onHeadersReceived.addListener(
    onHeadersReceived,
    FILTER,
    ["responseHeaders"]
  );
  browser.webRequest.onCompleted.addListener(onCompleted, FILTER);
  browser.webRequest.onErrorOccurred.addListener(onErrorOccurred, FILTER);
} catch (error) {
  // Report capability errors in the inspector instead of silently pretending capture works.
  setTimeout(() => sendToApp({ type: "error", message: String(error) }), 0);
}

connectToApp();
