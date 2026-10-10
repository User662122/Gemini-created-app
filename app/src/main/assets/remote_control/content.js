/*
 * Remote control bridge content script.
 *
 * Runs in every top-level page and opens a native-messaging port to the app. The app's localhost
 * control server sends commands ({id, cmd, args}) through that port and this script answers with
 * {id, ok, result} or {id, ok: false, error}.
 */
(() => {
  "use strict";

  const NATIVE_APP = "browser";
  let port = null;

  function connect() {
    if (port) return;
    let p;
    try {
      p = browser.runtime.connectNative(NATIVE_APP);
    } catch (e) {
      return;
    }
    port = p;
    p.onMessage.addListener((message) => handleMessage(message, p));
    p.onDisconnect.addListener(() => {
      if (port === p) port = null;
    });
    p.postMessage({ type: "hello", url: location.href });
  }

  // Pages kept in the back/forward cache must not answer for the page that replaced them.
  window.addEventListener("pagehide", () => {
    if (port) {
      const p = port;
      port = null;
      try {
        p.disconnect();
      } catch (e) {
        // already gone
      }
    }
  });
  window.addEventListener("pageshow", (event) => {
    if (event.persisted) connect();
  });

  async function handleMessage(message, p) {
    if (!message || typeof message.id !== "number") return;
    const reply = (payload) => {
      try {
        p.postMessage(Object.assign({ id: message.id }, payload));
      } catch (e) {
        // The page is going away; the app will report the navigation.
      }
    };
    try {
      const command = COMMANDS[message.cmd];
      if (!command) throw new Error(`Unknown command: ${message.cmd}`);
      const result = await command(message.args || {});
      reply({ ok: true, result: result === undefined ? null : result });
    } catch (e) {
      reply({ ok: false, error: errorText(e) });
    }
  }

  function errorText(e) {
    if (e && typeof e === "object" && "message" in e) {
      const name = e.name && e.name !== "Error" ? `${e.name}: ` : "";
      return name + e.message;
    }
    return String(e);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  function isElement(value) {
    return !!value && typeof value === "object" && value.nodeType === 1 && typeof value.tagName === "string";
  }

  function isNode(value) {
    return !!value && typeof value === "object" && typeof value.nodeType === "number" && "nodeName" in value;
  }

  function isVisible(el) {
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return false;
    const style = window.getComputedStyle(el);
    return !!style && style.visibility !== "hidden" && style.display !== "none" && style.opacity !== "0";
  }

  function rawText(el) {
    return String(typeof el.innerText === "string" ? el.innerText : el.textContent || "");
  }

  function textOf(el) {
    return rawText(el).replace(/\s+/g, " ").trim();
  }

  function describe(el) {
    const rect = el.getBoundingClientRect();
    const attr = (name) => (el.hasAttribute(name) ? el.getAttribute(name) : null);
    let value = null;
    if (typeof el.value === "string") value = el.value;
    let href = null;
    if (typeof el.href === "string") href = el.href;
    let src = null;
    if (typeof el.src === "string") src = el.src;
    return {
      tag: el.tagName.toLowerCase(),
      id: el.id || null,
      classes: Array.from(el.classList || []),
      name: attr("name"),
      type: attr("type"),
      role: attr("role"),
      placeholder: attr("placeholder"),
      aria_label: attr("aria-label"),
      text: textOf(el).slice(0, 300),
      value,
      href,
      src,
      checked: typeof el.checked === "boolean" ? el.checked : null,
      disabled: typeof el.disabled === "boolean" ? el.disabled : null,
      visible: isVisible(el),
      rect: {
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        width: Math.round(rect.width),
        height: Math.round(rect.height),
      },
    };
  }

  /** Converts any JS value into something JSON-safe. */
  function serialize(value, depth = 0, ancestors = []) {
    if (value === undefined || value === null) return null;
    const type = typeof value;
    if (type === "string" || type === "boolean") return value;
    if (type === "number") return Number.isFinite(value) ? value : String(value);
    if (type === "bigint" || type === "symbol") return value.toString();
    if (type === "function") return `[function ${value.name || "anonymous"}]`;
    if (depth > 8) return "[max depth]";
    if (ancestors.includes(value)) return "[circular]";
    const next = ancestors.concat([value]);
    try {
      if (isElement(value)) return describe(value);
      if (isNode(value)) return { node: value.nodeName, text: String(value.textContent || "").slice(0, 1000) };
      if (value === window) return `[window ${location.href}]`;
      if (typeof value.toISOString === "function" && typeof value.getTime === "function") {
        return value.toISOString();
      }
      if (typeof value.message === "string" && typeof value.stack === "string") {
        return { error: errorText(value), stack: value.stack };
      }
      if (Array.isArray(value) || (typeof value.length === "number" && typeof value.item === "function")) {
        return Array.from(value, (item) => serialize(item, depth + 1, next));
      }
      if (typeof value.entries === "function" && typeof value.has === "function") {
        const isMap = typeof value.get === "function";
        if (isMap) {
          const out = {};
          for (const [k, v] of value.entries()) out[String(k)] = serialize(v, depth + 1, next);
          return out;
        }
        return Array.from(value.values(), (item) => serialize(item, depth + 1, next));
      }
      const out = {};
      for (const key of Object.keys(value)) out[key] = serialize(value[key], depth + 1, next);
      return out;
    } catch (e) {
      try {
        return String(value);
      } catch (ignored) {
        return "[unserializable]";
      }
    }
  }

  /**
   * Finds elements. Besides CSS selectors this understands:
   *   "xpath=//button[1]"  – XPath expression
   *   "text=Sign in"       – smallest element whose visible text contains the words (case-insensitive)
   */
  function findAll(selector) {
    if (typeof selector !== "string" || !selector.trim()) throw new Error("A 'selector' is required");
    if (selector.startsWith("xpath=")) {
      const snapshot = document.evaluate(
        selector.slice(6), document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
      const out = [];
      for (let i = 0; i < snapshot.snapshotLength; i++) {
        const node = snapshot.snapshotItem(i);
        if (isElement(node)) out.push(node);
      }
      return out;
    }
    if (selector.startsWith("text=")) {
      const needle = selector.slice(5).replace(/\s+/g, " ").trim().toLowerCase();
      if (!needle) throw new Error("text= selector needs some text");
      const root = document.body || document.documentElement;
      const matches = [];
      for (const el of root.querySelectorAll("*")) {
        const tag = el.tagName.toLowerCase();
        if (tag === "script" || tag === "style" || tag === "noscript" || tag === "template") continue;
        const isInputButton = tag === "input" && /^(button|submit|reset)$/i.test(el.type || "");
        const own = (isInputButton ? String(el.value || "") : textOf(el)).toLowerCase();
        if (own.includes(needle)) matches.push(el);
      }
      // Keep the innermost matches (an element whose descendants don't also match), exact first.
      const innermost = matches.filter((el) => !matches.some((other) => other !== el && el.contains(other)));
      innermost.sort((a, b) => (textOf(a).toLowerCase() === needle ? 0 : 1) - (textOf(b).toLowerCase() === needle ? 0 : 1));
      return innermost;
    }
    return Array.from(document.querySelectorAll(selector));
  }

  function find(selector, index) {
    const all = findAll(selector);
    const i = Number.isInteger(index) ? index : 0;
    const el = all[i < 0 ? all.length + i : i];
    if (!el) {
      throw new Error(all.length
        ? `Only ${all.length} element(s) match ${JSON.stringify(selector)}; index ${i} is out of range`
        : `No element matches ${JSON.stringify(selector)}`);
    }
    return el;
  }

  function fire(el, type, init) {
    el.dispatchEvent(new Event(type, Object.assign({ bubbles: true, cancelable: true }, init || {})));
  }

  function pressEnter(el) {
    const init = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
    const proceed = el.dispatchEvent(new KeyboardEvent("keydown", init));
    el.dispatchEvent(new KeyboardEvent("keypress", init));
    el.dispatchEvent(new KeyboardEvent("keyup", init));
    if (proceed && el.form) {
      if (typeof el.form.requestSubmit === "function") el.form.requestSubmit();
      else el.form.submit();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Commands
  // ---------------------------------------------------------------------------------------------

  async function evalInContentWorld(code) {
    // Expressions may use `await`; multi-statement code should `return` its result.
    let fn;
    try {
      fn = new Function(`return (async () => (\n${code}\n))();`);
    } catch (e) {
      if (!(e instanceof SyntaxError)) throw e;
      fn = new Function(`return (async () => {\n${code}\n})();`);
    }
    return serialize(await fn.call(window));
  }

  async function evalInPageWorld(code) {
    // Runs with the page's own globals (e.g. variables set by the site's scripts).
    const pageWindow = window.wrappedJSObject;
    let result = pageWindow.eval(code);
    if (result && (typeof result === "object" || typeof result === "function") && typeof result.then === "function") {
      result = await new Promise((resolve, reject) => {
        result.then(
          exportFunction((value) => resolve(value), window),
          exportFunction((reason) => reject(new Error(String(reason && reason.message ? reason.message : reason))), window),
        );
      });
    }
    return serialize(result);
  }

  const COMMANDS = {
    ping() {
      return { url: location.href, title: document.title, ready_state: document.readyState };
    },

    eval(args) {
      if (typeof args.script !== "string") throw new Error("A 'script' string is required");
      return args.world === "page" ? evalInPageWorld(args.script) : evalInContentWorld(args.script);
    },

    click(args) {
      const el = find(args.selector, args.index);
      el.scrollIntoView({ block: "center", inline: "center" });
      if (typeof el.focus === "function") el.focus();
      el.click();
      return describe(el);
    },

    fill(args) {
      const el = find(args.selector, args.index);
      const value = args.value === undefined || args.value === null ? "" : String(args.value);
      el.scrollIntoView({ block: "center", inline: "center" });
      if (typeof el.focus === "function") el.focus();
      const tag = el.tagName.toLowerCase();
      if (el.isContentEditable) {
        el.textContent = value;
        fire(el, "input");
      } else if (tag === "select") {
        const option = Array.from(el.options).find((o) => o.value === value || textOf(o) === value);
        if (!option) throw new Error(`No <option> with value or text ${JSON.stringify(value)}`);
        el.value = option.value;
        fire(el, "input");
        fire(el, "change");
      } else if (tag === "input" && /^(checkbox|radio)$/i.test(el.type)) {
        const want = !/^(|0|false|off|no)$/i.test(value);
        if (el.checked !== want) el.click();
      } else if ("value" in el) {
        // Setting through the content-script view calls the native setter, so frameworks such as
        // React notice the change once the input event fires.
        el.value = value;
        fire(el, "input");
        fire(el, "change");
      } else {
        throw new Error(`<${tag}> is not a form field`);
      }
      if (args.submit) pressEnter(el);
      return describe(el);
    },

    html(args) {
      if (args.selector) return find(args.selector, args.index).outerHTML;
      return document.documentElement ? document.documentElement.outerHTML : "";
    },

    text(args) {
      if (args.selector) return textOf(find(args.selector, args.index));
      return document.body ? rawText(document.body) : "";
    },

    query(args) {
      const limit = Number.isInteger(args.limit) && args.limit > 0 ? args.limit : 50;
      return findAll(args.selector).slice(0, limit).map(describe);
    },

    async wait_for(args) {
      const timeout = typeof args.timeout === "number" ? args.timeout * 1000 : 10000;
      const deadline = Date.now() + timeout;
      const wantGone = !!args.gone;
      for (;;) {
        let matches = [];
        try {
          matches = findAll(args.selector);
        } catch (e) {
          if (e && e.name === "SyntaxError") throw e;
        }
        if (args.visible) matches = matches.filter(isVisible);
        if (wantGone ? matches.length === 0 : matches.length > 0) {
          return wantGone ? true : describe(matches[0]);
        }
        if (Date.now() >= deadline) {
          throw new Error(`Timed out after ${timeout / 1000}s waiting for ${JSON.stringify(args.selector)}` +
            (wantGone ? " to disappear" : args.visible ? " to be visible" : ""));
        }
        await sleep(100);
      }
    },

    scroll(args) {
      if (args.selector) {
        find(args.selector, args.index).scrollIntoView({ block: "center", inline: "nearest" });
      } else if (args.to === "top") {
        window.scrollTo(0, 0);
      } else if (args.to === "bottom") {
        const root = document.scrollingElement || document.documentElement;
        window.scrollTo(0, root ? root.scrollHeight : 0);
      } else {
        window.scrollBy(Number(args.x) || 0, Number(args.y) || 0);
      }
      return { x: Math.round(window.scrollX), y: Math.round(window.scrollY) };
    },
  };

  connect();
})();
