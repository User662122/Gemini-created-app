/*
 * Browser Bridge — the app <-> page channel for GeckoView.
 *
 * GeckoView has no addJavascriptInterface and no evaluateJavascript: a built-in WebExtension using
 * native messaging is the supported way for an embedder to talk to page content. This content script
 * runs in an isolated world that shares the page's DOM, which is exactly what is needed here:
 *
 *   - automation playback: query an element, click it, or set its value and fire input/change;
 *   - automation recording: observe click/input/change on the document and report the element;
 *   - uncaught page errors: report them, since the app can no longer read the console directly.
 *
 * It deliberately does NOT touch page-world JavaScript objects (window.fetch, console.log): a content
 * script cannot replace them, and pretending otherwise would produce a capture that silently misses
 * most traffic.
 *
 * Every message the app sends carries a "requestId" when it expects an answer, and every answer
 * carries the same id back, so a slow step can time out on the app side without losing the reply
 * that arrives afterwards.
 */
(function () {
  "use strict";

  const NATIVE_APP = "browserbridge";
  const MAX_SELECTOR_LENGTH = 1000;
  const MAX_VALUE_LENGTH = 2000;
  const INPUT_DEBOUNCE_MS = 400;

  let port = null;
  let recording = false;

  // ------------------------------------------------------------------ transport

  function connect() {
    try {
      port = browser.runtime.connectNative(NATIVE_APP);
      port.onMessage.addListener(handleAppMessage);
      port.onDisconnect.addListener(function () {
        port = null;
        // The app closed the port (a new document, a closed tab). Reconnecting is the app's job when
        // it next asks this page for something; a page must not spin trying to reconnect.
      });
    } catch (error) {
      port = null;
    }
  }

  function send(message) {
    if (!port) return false;
    try {
      port.postMessage(message);
      return true;
    } catch (error) {
      // The app went away (a closed tab, a replaced delegate). Nothing to do but wait: it is the
      // app's job to re-connect when it next needs this page.
      port = null;
      return false;
    }
  }

  function handleAppMessage(message) {
    if (!message || typeof message !== "object") return;
    switch (message.type) {
      case "set-recorder":
        recording = message.enabled === true;
        break;
      case "automation-step":
        send({
          type: "automation-step-result",
          requestId: message.requestId,
          result: runStep(message)
        });
        break;
      default:
        break;
    }
  }

  // ------------------------------------------------------------------ playback

  function runStep(step) {
    try {
      if (!step || typeof step.selector !== "string" || step.selector.length > MAX_SELECTOR_LENGTH) {
        return "error";
      }
      const element = document.querySelector(step.selector);
      if (!element) return "missing";

      element.scrollIntoView(true);

      if (step.action === "click") {
        element.click();
        return "done";
      }

      if (!element.isConnected) return "missing";

      element.focus();
      const value = String(step.value == null ? "" : step.value).slice(0, MAX_VALUE_LENGTH);
      if ("value" in element) {
        // Use the prototype's setter so frameworks that watch for native value changes see it.
        const prototype = Object.getPrototypeOf(element);
        const descriptor = prototype && Object.getOwnPropertyDescriptor(prototype, "value");
        if (descriptor && descriptor.set) descriptor.set.call(element, value);
        else element.value = value;
      } else if (element.isContentEditable) {
        element.textContent = value;
      }
      element.dispatchEvent(new Event("input", { bubbles: true }));
      element.dispatchEvent(new Event("change", { bubbles: true }));
      return "done";
    } catch (error) {
      return "error";
    }
  }

  // ------------------------------------------------------------------ recording

  function escapeCss(value) {
    if (window.CSS && window.CSS.escape) return window.CSS.escape(value);
    return String(value).replace(/([^a-zA-Z0-9_-])/g, function (character) {
      return "\\" + character;
    });
  }

  function isUnique(selector) {
    try {
      return document.querySelectorAll(selector).length === 1;
    } catch (_) {
      return false;
    }
  }

  function selectorFor(element) {
    if (!element || !element.tagName) return "";
    if (element.id) {
      const idSelector = "#" + escapeCss(element.id);
      if (isUnique(idSelector)) return idSelector;
    }
    const tag = element.tagName.toLowerCase();
    const attributes = ["data-testid", "name", "aria-label"];
    for (let index = 0; index < attributes.length; index += 1) {
      const value = element.getAttribute(attributes[index]);
      if (!value) continue;
      const selector =
        tag + "[" + attributes[index] + '="' + value.replace(/\\/g, "\\\\").replace(/"/g, '\\"') + '"]';
      if (isUnique(selector)) return selector;
    }
    const parts = [];
    let current = element;
    while (current && current.nodeType === 1 && current !== document.documentElement) {
      let part = current.tagName.toLowerCase();
      const parent = current.parentElement;
      if (parent) {
        const siblings = Array.prototype.filter.call(parent.children, function (child) {
          return child.tagName === current.tagName;
        });
        if (siblings.length > 1) part += ":nth-of-type(" + (siblings.indexOf(current) + 1) + ")";
      }
      parts.unshift(part);
      current = parent;
    }
    return parts.join(" > ");
  }

  function isSensitive(element) {
    const type = (element.type || "").toLowerCase();
    const hints = [element.name, element.id, element.autocomplete, element.getAttribute("aria-label")]
      .join(" ")
      .toLowerCase();
    return (
      type === "password" ||
      type === "file" ||
      type === "hidden" ||
      /password|passcode|one-time|otp|credit.?card|card.?number|cvv|cvc|ssn|social.?security/.test(hints)
    );
  }

  function isTextInput(element) {
    if (element.isContentEditable) return true;
    const tag = element.tagName && element.tagName.toLowerCase();
    if (tag === "textarea" || tag === "select") return true;
    if (tag !== "input") return false;
    return !/^(button|submit|reset|checkbox|radio|file|hidden|image|password)$/i.test(element.type || "text");
  }

  function reportStep(action, element, value) {
    if (!recording) return;
    const selector = selectorFor(element);
    if (!selector) return;
    send({
      type: "recorded-step",
      action: action,
      selector: selector,
      value: value == null ? "" : String(value).slice(0, MAX_VALUE_LENGTH)
    });
  }

  const pendingInputs = new WeakMap();
  const pendingElements = new Set();

  function flushPendingInputs() {
    pendingElements.forEach(function (element) {
      clearTimeout(pendingInputs.get(element));
      pendingInputs.delete(element);
      if (document.documentElement.contains(element) && !isSensitive(element)) {
        reportStep("input", element, element.isContentEditable ? element.textContent : element.value);
      }
    });
    pendingElements.clear();
  }

  document.addEventListener(
    "click",
    function (event) {
      if (!recording) return;
      flushPendingInputs();
      const target = event.target;
      const element =
        target && target.closest
          ? target.closest(
              "a,button,input[type=button],input[type=submit],input[type=checkbox],input[type=radio],[role=button],[onclick]"
            )
          : target;
      if (element && !isSensitive(element)) reportStep("click", element, "");
    },
    true
  );

  document.addEventListener(
    "input",
    function (event) {
      if (!recording) return;
      const element = event.target;
      if (!element || !isTextInput(element) || isSensitive(element)) return;
      clearTimeout(pendingInputs.get(element));
      pendingElements.add(element);
      pendingInputs.set(
        element,
        setTimeout(function () {
          pendingElements.delete(element);
          if (document.documentElement.contains(element)) {
            reportStep("input", element, element.isContentEditable ? element.textContent : element.value);
          }
        }, INPUT_DEBOUNCE_MS)
      );
    },
    true
  );

  document.addEventListener(
    "change",
    function (event) {
      if (!recording) return;
      const element = event.target;
      if (!element || !isTextInput(element) || isSensitive(element)) return;
      clearTimeout(pendingInputs.get(element));
      pendingElements.delete(element);
      reportStep("input", element, element.isContentEditable ? element.textContent : element.value);
    },
    true
  );

  // ------------------------------------------------------------------ errors

  // A content script cannot replace console.log, but it does receive uncaught errors: the event is
  // dispatched on the shared window, so reporting them costs nothing and needs no page-world hooks.
  window.addEventListener(
    "error",
    function (event) {
      try {
        const message = event && event.message ? String(event.message) : "Uncaught error";
        send({
          type: "page-error",
          message: message.slice(0, 2000),
          source: event && event.filename ? String(event.filename) : "",
          line: event && event.lineno ? event.lineno : 0
        });
      } catch (_) {
        /* reporting must never break the page */
      }
    },
    true
  );

  // ------------------------------------------------------------------ start

  connect();
})();
