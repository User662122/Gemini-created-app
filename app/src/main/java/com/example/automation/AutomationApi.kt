package com.example.automation

import com.example.ui.BrowserAddress
import com.example.ui.BrowserTabState
import com.example.ui.BrowserViewModel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Maps the local REST API onto browser tabs and page operations.
 *
 * Endpoints (all under /api/v1, JSON in and out):
 *   GET    /status
 *   GET    /tabs                       POST /tabs {url?}
 *   GET    /tabs/{id}                  DELETE /tabs/{id}
 *   POST   /tabs/{id}/select
 *   POST   /tabs/{id}/navigate {url}
 *   POST   /tabs/{id}/reload | back | forward | stop
 *   POST   /tabs/{id}/eval {code}      -> {result}
 *   POST   /tabs/{id}/dom {op, selector, ...} -> {result}
 */
internal class AutomationApi(
    private val viewModel: BrowserViewModel,
    private val bridge: AutomationBridge,
    private val expectedToken: () -> String,
) {

    fun handle(request: HttpRequest): HttpResponse {
        return try {
            authorize(request)
            route(request)
        } catch (error: HttpException) {
            HttpResponse.error(error.status, error.message ?: "Request failed")
        } catch (error: BridgeException) {
            HttpResponse.error(error.status, error.message ?: "Page operation failed")
        } catch (error: JSONException) {
            HttpResponse.error(400, "The request body must be a JSON object")
        } catch (error: Exception) {
            HttpResponse.error(500, error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Only local, non-browser clients with the token may call the API:
     * - Browser pages always send an Origin header, so any request with one is refused.
     * - The Host check blocks DNS-rebinding attacks.
     * - The token protects against other apps on the device.
     */
    private fun authorize(request: HttpRequest) {
        if (request.header("origin") != null) {
            throw HttpException(403, "Requests from web pages are not allowed")
        }
        val host = request.header("host")?.substringBefore(':').orEmpty()
        if (host != "127.0.0.1" && host != "localhost") {
            throw HttpException(403, "The Host header must be localhost or 127.0.0.1")
        }
        val provided = request.header(TOKEN_HEADER).orEmpty().toByteArray(Charsets.UTF_8)
        val expected = expectedToken().toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(provided, expected)) {
            throw HttpException(401, "Missing or invalid $TOKEN_HEADER header")
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        val segments = request.path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "api" || segments[1] != "v1") {
            throw HttpException(404, "Unknown endpoint: ${request.path}")
        }
        val rest = segments.drop(2)
        val method = request.method
        return when {
            rest == listOf("status") && method == "GET" -> status()
            rest == listOf("tabs") && method == "GET" -> {
                val tabs = runOnMainThread { viewModel.tabs.map { tabJson(it) } }
                ok(JSONArray(tabs))
            }
            rest == listOf("tabs") && method == "POST" -> createTab(request)
            rest.size >= 2 && rest[0] == "tabs" -> tabRoute(request, rest[1], rest.drop(2))
            else -> throw HttpException(404, "Unknown endpoint: ${request.method} ${request.path}")
        }
    }

    private fun status(): HttpResponse {
        val json = runOnMainThread {
            JSONObject()
                .put("ok", true)
                .put("apiVersion", API_VERSION)
                .put("tabs", viewModel.tabs.size)
                .put("activeTabId", viewModel.activeTabId ?: JSONObject.NULL)
                .put("extensionReady", bridge.isReady)
        }
        return ok(json)
    }

    private fun createTab(request: HttpRequest): HttpResponse {
        val input = request.jsonBody().optString("url", "").trim()
        val url = if (input.isEmpty()) {
            null
        } else {
            BrowserAddress.resolve(input) ?: throw HttpException(400, "Invalid URL: $input")
        }
        val json = runOnMainThread { tabJson(viewModel.openNewTab(url)) }
        return HttpResponse(201, json.toString())
    }

    private fun tabRoute(request: HttpRequest, tabId: String, rest: List<String>): HttpResponse {
        val method = request.method
        val tab = runOnMainThread { viewModel.findTab(tabId) }
            ?: throw HttpException(404, "No tab with id $tabId")

        return when {
            rest.isEmpty() && method == "GET" -> ok(runOnMainThread { tabJson(tab) })
            rest.isEmpty() && method == "DELETE" -> {
                runOnMainThread { viewModel.closeTab(tabId) }
                ok(JSONObject().put("closed", true))
            }
            rest == listOf("select") && method == "POST" -> {
                runOnMainThread { viewModel.selectTab(tabId) }
                ok(runOnMainThread { tabJson(tab) })
            }
            rest == listOf("navigate") && method == "POST" -> navigate(request, tabId, tab)
            rest == listOf("reload") && method == "POST" -> tabCommand(tab) {
                if (tab.url.isNotBlank()) tab.session.reload()
            }
            rest == listOf("back") && method == "POST" -> tabCommand(tab) {
                if (tab.canGoBack) tab.session.goBack()
            }
            rest == listOf("forward") && method == "POST" -> tabCommand(tab) {
                if (tab.canGoForward) tab.session.goForward()
            }
            rest == listOf("stop") && method == "POST" -> tabCommand(tab) {
                tab.session.stop()
                tab.isLoading = false
            }
            rest == listOf("eval") && method == "POST" -> {
                val code = request.jsonBody().optString("code")
                if (code.isBlank()) throw HttpException(400, "\"code\" is required")
                val result = bridge.request(tabId, "eval", JSONObject().put("code", code))
                ok(JSONObject().put("result", result))
            }
            rest == listOf("dom") && method == "POST" -> domOperation(request, tabId)
            else -> throw HttpException(404, "Unknown endpoint: $method ${request.path}")
        }
    }

    private fun navigate(request: HttpRequest, tabId: String, tab: BrowserTabState): HttpResponse {
        val input = request.jsonBody().optString("url", "").trim()
        val url = BrowserAddress.resolve(input) ?: throw HttpException(400, "\"url\" is required")
        runOnMainThread { viewModel.loadInTab(tabId, url) }
        return ok(runOnMainThread { tabJson(tab) })
    }

    private fun domOperation(request: HttpRequest, tabId: String): HttpResponse {
        val body = request.jsonBody()
        val op = body.optString("op")
        if (op !in DOM_OPERATIONS) {
            throw HttpException(400, "\"op\" must be one of: ${DOM_OPERATIONS.joinToString()}")
        }
        val args = JSONObject()
        for (key in DOM_ARGUMENTS) {
            if (body.has(key)) args.put(key, body.get(key))
        }
        val result = bridge.request(tabId, op, args)
        return ok(JSONObject().put("result", result))
    }

    /** Runs [action] on the main thread and returns the tab's state afterwards. */
    private fun tabCommand(tab: BrowserTabState, action: () -> Unit): HttpResponse =
        runOnMainThread {
            action()
            ok(tabJson(tab))
        }

    /** Must be called on the main thread because it reads observable tab state. */
    private fun tabJson(tab: BrowserTabState): JSONObject = JSONObject()
        .put("id", tab.id)
        .put("url", tab.url)
        .put("title", tab.title)
        .put("loading", tab.isLoading)
        .put("progress", tab.progress)
        .put("canGoBack", tab.canGoBack)
        .put("canGoForward", tab.canGoForward)
        .put("active", tab.id == viewModel.activeTabId)

    private fun ok(json: Any): HttpResponse = HttpResponse(200, json.toString())

    companion object {
        const val TOKEN_HEADER = "X-Automation-Token"
        const val API_VERSION = 1

        private val DOM_OPERATIONS = setOf("text", "html", "attr", "value", "exists", "click", "type")
        private val DOM_ARGUMENTS = listOf("selector", "name", "text", "inner")
    }
}
