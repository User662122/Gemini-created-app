package com.example.remote

import android.graphics.Bitmap
import com.example.ui.BrowserTabState
import com.example.ui.BrowserViewModel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * JSON-over-HTTP API served on 127.0.0.1. Every endpoint accepts GET (query parameters) or POST
 * (JSON object body, merged over the query parameters). See termux/README.md for the reference.
 *
 * Runs on server worker threads; browser state is only touched through [MainThread].
 */
internal class RemoteApi(private val tokenProvider: () -> String, private val appVersion: String) {

    fun handle(request: HttpRequest): HttpResponse {
        if (!RemoteAuth.isLoopbackHost(request.headers["host"])) {
            return error(403, "Only localhost Host headers are accepted")
        }
        if (RemoteAuth.isFromWebPage(request) || request.method == "OPTIONS") {
            return error(403, "Requests from web pages are not allowed")
        }
        if (request.method !in setOf("GET", "POST", "HEAD")) {
            return error(405, "Use GET or POST")
        }
        if (request.path == "/") {
            return json(
                JSONObject()
                    .put("ok", true)
                    .put("name", "Gecko Browser remote control")
                    .put("version", appVersion)
                    .put("auth", "Send the token from the app as 'Authorization: Bearer <token>'")
                    .put("endpoints", JSONArray(ENDPOINTS)),
            )
        }
        if (!RemoteAuth.isAuthorized(request, tokenProvider())) {
            return error(401, "Missing or wrong token. Copy it from the browser: Remote control dialog.")
        }

        return try {
            route(request.path, Params.from(request))
        } catch (e: HttpException) {
            error(e.status, e.message ?: "Error")
        } catch (e: PageNotReadyException) {
            error(409, e.message ?: "Page not ready")
        } catch (e: PageScriptException) {
            error(400, e.message ?: "Page error", "page_error")
        } catch (e: PageNavigatedException) {
            error(409, e.message ?: "Page navigated", "navigated")
        } catch (e: TimeoutException) {
            error(504, e.message ?: "Timed out")
        }
    }

    private fun route(path: String, p: Params): HttpResponse = when (path) {
        "/status" -> status()
        "/tabs" -> json(ok().put("tabs", onMain { vm -> JSONArray(vm.tabs.mapIndexed { i, t -> tabJson(vm, t, i) }) }))
        "/tab" -> json(ok().put("tab", onMain { vm -> tabJson(vm, p.tab(vm)) }))
        "/tabs/new" -> newTab(p)
        "/tabs/activate" -> json(ok().put("tab", onMain { vm ->
            val tab = p.tab(vm, required = true)
            vm.selectTab(tab.id)
            tabJson(vm, tab)
        }))
        "/tabs/close" -> json(ok().put("closed", onMain { vm ->
            val tab = p.tab(vm)
            vm.closeTab(tab.id)
            tab.id
        }))
        "/navigate" -> navigate(p)
        "/back" -> historyAction(p) { vm, tab -> vm.goBack(tab) }
        "/forward" -> historyAction(p) { vm, tab -> vm.goForward(tab) }
        "/reload" -> historyAction(p) { vm, tab -> vm.reload(tab) }
        "/stop" -> json(ok().put("tab", onMain { vm ->
            val tab = p.tab(vm)
            vm.stopLoading(tab)
            tabJson(vm, tab)
        }))
        "/wait_load" -> {
            val tabId = onMain { vm -> p.tab(vm).id }
            json(ok().put("tab", waitForLoad(tabId, p.timeoutMs(30.0), expectStart = false)))
        }
        "/eval" -> {
            val args = JSONObject()
                .put("script", p.string("script") ?: p.string("js") ?: throw HttpException(400, "'script' is required"))
                .put("world", p.string("world") ?: "content")
            pageResult(p, "eval", args)
        }
        "/click" -> pageResult(p, "click", p.pageArgs("selector", "index"))
        "/fill" -> pageResult(p, "fill", p.pageArgs("selector", "index", "value", "submit"))
        "/html" -> pageResult(p, "html", p.pageArgs("selector", "index"))
        "/text" -> pageResult(p, "text", p.pageArgs("selector", "index"))
        "/query" -> pageResult(p, "query", p.pageArgs("selector", "limit"))
        "/wait_for" -> {
            val timeoutSeconds = p.double("timeout") ?: 10.0
            val args = p.pageArgs("selector", "visible", "gone").put("timeout", timeoutSeconds)
            pageResult(p, "wait_for", args, timeoutMs = (timeoutSeconds * 1000).toLong() + 5_000)
        }
        "/scroll" -> pageResult(p, "scroll", p.pageArgs("selector", "index", "x", "y", "to"))
        "/screenshot" -> screenshot(p)
        else -> error(404, "Unknown endpoint $path. GET / lists the endpoints.")
    }

    // --------------------------------------------------------------------------------------------

    private fun status(): HttpResponse {
        val info = onMain { vm ->
            val active = vm.activeTab
            JSONObject()
                .put("ok", true)
                .put("version", appVersion)
                .put("tab_count", vm.tabs.size)
                .put("active_tab", active?.let { tabJson(vm, it) } ?: JSONObject.NULL)
                .put("page_bridge_ready", vm.pageBridge.isReady)
                .put("page_bridge_error", vm.pageBridge.installError ?: JSONObject.NULL)
        }
        return json(info)
    }

    private fun newTab(p: Params): HttpResponse {
        val wait = p.bool("wait") ?: false
        val tabId = onMain { vm ->
            val url = p.string("url")?.let { address ->
                com.example.ui.BrowserAddress.resolve(address) ?: throw HttpException(400, "Empty url")
            }
            vm.openNewTab(url, activate = p.bool("activate") ?: true).id
        }
        val tab = if (wait && p.string("url") != null) {
            waitForLoad(tabId, p.timeoutMs(30.0), expectStart = false)
        } else {
            onMain { vm -> tabJson(vm, vm.findTab(tabId) ?: throw HttpException(404, "Tab closed")) }
        }
        return json(ok().put("tab", tab))
    }

    private fun navigate(p: Params): HttpResponse {
        val address = p.string("url") ?: throw HttpException(400, "'url' is required")
        val tabId = onMain { vm ->
            val tab = p.tab(vm)
            vm.navigate(tab, address) ?: throw HttpException(400, "Empty url")
            tab.id
        }
        val tab = if (p.bool("wait") == true) {
            waitForLoad(tabId, p.timeoutMs(30.0), expectStart = false)
        } else {
            onMain { vm -> tabJson(vm, vm.findTab(tabId) ?: throw HttpException(404, "Tab closed")) }
        }
        return json(ok().put("tab", tab))
    }

    private fun historyAction(p: Params, action: (BrowserViewModel, BrowserTabState) -> Boolean): HttpResponse {
        val (tabId, done) = onMain { vm ->
            val tab = p.tab(vm)
            tab.id to action(vm, tab)
        }
        if (!done) return error(409, "Nothing to do (no history entry or blank page)")
        val tab = if (p.bool("wait") == true) {
            waitForLoad(tabId, p.timeoutMs(30.0), expectStart = true)
        } else {
            onMain { vm -> tabJson(vm, vm.findTab(tabId) ?: throw HttpException(404, "Tab closed")) }
        }
        return json(ok().put("tab", tab))
    }

    /**
     * Waits until the tab finished loading. With [expectStart] it first gives the navigation up to
     * two seconds to begin, because back/forward/reload start loading asynchronously.
     */
    private fun waitForLoad(tabId: String, timeoutMs: Long, expectStart: Boolean): JSONObject {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        var seenLoading = !expectStart
        while (true) {
            val (loading, info) = onMain { vm ->
                val tab = vm.findTab(tabId)?.takeIf { it.id == tabId } ?: throw HttpException(404, "Tab was closed")
                tab.isLoading to tabJson(vm, tab)
            }
            if (loading) seenLoading = true
            val now = System.currentTimeMillis()
            if (!loading && (seenLoading || now - start > 2_000)) return info
            if (now >= deadline) throw TimeoutException("Page still loading after ${timeoutMs / 1000.0}s")
            Thread.sleep(100)
        }
    }

    private fun pageResult(p: Params, command: String, args: JSONObject, timeoutMs: Long = p.timeoutMs(30.0)): HttpResponse {
        val tabId = onMain { vm -> p.tab(vm).id }
        return try {
            json(ok().put("result", runInPage(tabId, command, args, timeoutMs) ?: JSONObject.NULL))
        } catch (e: PageNavigatedException) {
            // Typical for click/fill+submit: the command worked and the page started navigating.
            if (command == "eval" || command == "click" || command == "fill") {
                json(ok().put("result", JSONObject.NULL).put("navigated", true))
            } else {
                throw e
            }
        }
    }

    private fun runInPage(tabId: String, command: String, args: JSONObject, timeoutMs: Long): Any? {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        // Give a loading page a moment to start its content script.
        while (true) {
            val (connected, loading) = onMain { vm ->
                val tab = vm.findTab(tabId)?.takeIf { it.id == tabId } ?: throw HttpException(404, "Tab was closed")
                vm.pageBridge.isConnected(tabId) to tab.isLoading
            }
            if (connected) break
            val now = System.currentTimeMillis()
            if (now >= deadline || (!loading && now - start > 2_000)) {
                throw PageNotReadyException(onMain { vm -> vm.pageBridge.notReadyMessage() })
            }
            Thread.sleep(100)
        }
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000)
        val future = onMain { vm -> vm.pageBridge.send(tabId, command, args, remaining) }
        return await(future, remaining + 5_000)
    }

    private fun screenshot(p: Params): HttpResponse {
        val future = onMain { vm ->
            val tab = p.tab(vm)
            if (tab.id != vm.activeTab?.id) {
                throw HttpException(409, "Only the visible tab can be captured; activate it first")
            }
            val view = vm.viewFor(tab.id)
                ?: throw HttpException(409, "The browser is not on screen; bring it to the foreground")
            val result = CompletableFuture<Bitmap>()
            view.capturePixels().accept(
                { bitmap -> if (bitmap != null) result.complete(bitmap) else result.completeExceptionally(IllegalStateException("No image")) },
                { e -> result.completeExceptionally(e ?: IllegalStateException("Capture failed")) },
            )
            result
        }
        val bitmap = try {
            await(future, 15_000)
        } catch (e: TimeoutException) {
            throw e
        } catch (e: HttpException) {
            throw e
        } catch (e: Exception) {
            throw HttpException(409, "Screenshot failed (keep the browser visible on screen): ${e.message}")
        }
        val png = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
        return HttpResponse(200, "image/png", png.toByteArray())
    }

    // --------------------------------------------------------------------------------------------

    private fun <T> onMain(block: (BrowserViewModel) -> T): T = MainThread.call {
        val vm = RemoteControl.browser
            ?: throw HttpException(503, "The browser window is not open. Launch the browser app first.")
        block(vm)
    }

    private fun <T> await(future: CompletableFuture<T>, timeoutMs: Long): T =
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    private fun tabJson(vm: BrowserViewModel, tab: BrowserTabState, index: Int = vm.tabs.indexOf(tab)): JSONObject =
        JSONObject()
            .put("id", tab.id)
            .put("index", index)
            .put("active", tab.id == vm.activeTab?.id)
            .put("url", tab.url)
            .put("title", tab.title)
            .put("loading", tab.isLoading)
            .put("progress", tab.progress)
            .put("can_go_back", tab.canGoBack)
            .put("can_go_forward", tab.canGoForward)
            .put("scriptable", vm.pageBridge.isConnected(tab.id))

    private fun ok() = JSONObject().put("ok", true)

    private fun json(body: JSONObject, status: Int = 200) =
        HttpResponse.text(status, body.toString(), "application/json; charset=utf-8")

    private fun error(status: Int, message: String, code: String? = null): HttpResponse {
        val body = JSONObject().put("ok", false).put("error", message)
        if (code != null) body.put("code", code)
        return json(body, status)
    }

    /** Request parameters: query string merged with a JSON object body. */
    private class Params(private val values: JSONObject) {

        fun string(name: String): String? =
            values.opt(name)?.takeUnless { it == JSONObject.NULL }?.toString()

        fun double(name: String): Double? = when (val v = values.opt(name)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: throw HttpException(400, "'$name' must be a number")
            else -> null
        }

        fun bool(name: String): Boolean? = when (val v = values.opt(name)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.lowercase() in setOf("1", "true", "yes", "on")
            else -> null
        }

        fun timeoutMs(defaultSeconds: Double): Long =
            ((double("timeout") ?: defaultSeconds).coerceIn(0.1, 600.0) * 1000).toLong()

        fun tab(vm: BrowserViewModel, required: Boolean = false): BrowserTabState {
            val ref = string("tab")
            if (required && ref.isNullOrBlank()) throw HttpException(400, "'tab' (id or index) is required")
            return vm.findTab(ref) ?: throw HttpException(404, "No tab matches '${ref.orEmpty()}'")
        }

        /** Copies the named parameters for a content-script command, converting numeric strings. */
        fun pageArgs(vararg names: String): JSONObject {
            val args = JSONObject()
            for (name in names) {
                val v = values.opt(name) ?: continue
                if (v == JSONObject.NULL) continue
                val converted = when {
                    name in INT_ARGS && v is String -> v.toIntOrNull() ?: throw HttpException(400, "'$name' must be an integer")
                    name in NUMBER_ARGS && v is String -> v.toDoubleOrNull() ?: throw HttpException(400, "'$name' must be a number")
                    name in BOOL_ARGS -> bool(name) ?: false
                    else -> v
                }
                args.put(name, converted)
            }
            return args
        }

        companion object {
            val INT_ARGS = setOf("index", "limit")
            val NUMBER_ARGS = setOf("x", "y")
            val BOOL_ARGS = setOf("submit", "visible", "gone")

            fun from(request: HttpRequest): Params {
                val values = JSONObject()
                request.query.forEach { (k, v) -> if (k != "token") values.put(k, v) }
                val text = request.bodyText.trim()
                if (text.isNotEmpty()) {
                    val body = try {
                        JSONTokener(text).nextValue()
                    } catch (e: JSONException) {
                        throw HttpException(400, "Body must be a JSON object: ${e.message}")
                    }
                    if (body !is JSONObject) throw HttpException(400, "Body must be a JSON object")
                    body.keys().forEach { key -> values.put(key, body.get(key)) }
                }
                return Params(values)
            }
        }
    }

    private companion object {
        val ENDPOINTS = listOf(
            "GET  /status",
            "GET  /tabs",
            "GET  /tab            {tab}",
            "POST /tabs/new       {url, activate=true, wait=false}",
            "POST /tabs/activate  {tab}",
            "POST /tabs/close     {tab}",
            "POST /navigate       {url, tab, wait=false, timeout=30}",
            "POST /back | /forward | /reload  {tab, wait=false}",
            "POST /stop           {tab}",
            "POST /wait_load      {tab, timeout=30}",
            "POST /eval           {script, world=content|page, tab, timeout=30}",
            "POST /click          {selector, index, tab}",
            "POST /fill           {selector, value, submit=false, index, tab}",
            "GET  /html           {selector, tab}",
            "GET  /text           {selector, tab}",
            "GET  /query          {selector, limit=50, tab}",
            "POST /wait_for       {selector, timeout=10, visible=false, gone=false, tab}",
            "POST /scroll         {x, y | selector | to=top|bottom, tab}",
            "GET  /screenshot     (PNG of the visible tab)",
        )
    }
}
