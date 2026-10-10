/*
 * Automation Bridge content script.
 *
 * Runs in every top-level http(s) page. It opens a native port to the app
 * ("automation"), receives requests from the localhost automation API, runs the
 * operation inside the page's own JavaScript world, and sends the result back.
 */
(function () {
  "use strict";

  if (window.top !== window) {
    return;
  }

  var port;
  try {
    port = browser.runtime.connectNative("automation");
  } catch (error) {
    return;
  }

  port.onMessage.addListener(function (request) {
    var reply;
    try {
      reply = runRequest(request);
    } catch (error) {
      reply = { ok: false, error: String((error && error.message) || error) };
    }
    reply.id = request && request.id;
    port.postMessage(reply);
  });

  function runRequest(request) {
    var args = request.args || {};
    // pageMain is serialized and evaluated in the page's realm, so page scripts
    // see the same DOM and globals a user would see in the console.
    var source =
      "(" + pageMain.toString() + ")(" +
      JSON.stringify(String(request.op)) + ", " +
      JSON.stringify(args) + ")";
    var raw;
    try {
      raw = window.wrappedJSObject.eval(source);
    } catch (error) {
      return {
        ok: false,
        error: "The page refused script execution: " + String(error),
      };
    }
    return JSON.parse(raw);
  }

  /*
   * Self-contained: it must not reference anything outside its own body,
   * because only its source text is sent into the page.
   * Returns a JSON string: {"ok": true, "value": ...} or {"ok": false, "error": "..."}.
   */
  function pageMain(op, args) {
    function out(ok, value, error) {
      return JSON.stringify(
        ok
          ? { ok: true, value: value === undefined ? null : value }
          : { ok: false, error: String(error) }
      );
    }

    function need(selector) {
      if (!selector) {
        throw new Error("A selector is required");
      }
      var element = document.querySelector(selector);
      if (!element) {
        throw new Error("No element matches selector: " + selector);
      }
      return element;
    }

    function setFieldValue(element, text) {
      // Use the native setter so frameworks such as React see the change.
      var descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), "value");
      if (descriptor && descriptor.set) {
        descriptor.set.call(element, text);
      } else {
        element.value = text;
      }
    }

    try {
      switch (op) {
        case "eval":
          // Indirect eval runs the code in the page's global scope.
          return out(true, (0, eval)(args.code));
        case "text": {
          var target = need(args.selector);
          return out(true, target.innerText || target.textContent || "");
        }
        case "html": {
          var root = args.selector ? need(args.selector) : document.documentElement;
          return out(true, args.inner ? root.innerHTML : root.outerHTML);
        }
        case "attr":
          return out(true, need(args.selector).getAttribute(args.name));
        case "value":
          return out(true, need(args.selector).value);
        case "exists":
          if (!args.selector) {
            throw new Error("A selector is required");
          }
          return out(true, document.querySelector(args.selector) !== null);
        case "click":
          need(args.selector).click();
          return out(true, true);
        case "type": {
          var field = need(args.selector);
          field.focus();
          var editable = field.isContentEditable || field.getAttribute("contenteditable") === "true";
          if (editable) {
            field.textContent = String(args.text);
          } else {
            setFieldValue(field, String(args.text));
          }
          field.dispatchEvent(new Event("input", { bubbles: true }));
          field.dispatchEvent(new Event("change", { bubbles: true }));
          return out(true, true);
        }
        default:
          return out(false, null, "Unknown operation: " + op);
      }
    } catch (error) {
      return out(false, null, (error && error.message) || error);
    }
  }
})();
