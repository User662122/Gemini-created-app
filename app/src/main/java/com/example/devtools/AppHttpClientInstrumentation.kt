package com.example.devtools

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Pseudo tab id for traffic produced by the app's own HTTP clients rather than by the WebView. */
const val APP_CLIENT_TAB_ID = "app-http"

/**
 * Optional hook for HTTP clients the app owns (OkHttp).
 *
 * Why it exists: WebView only reports the headers it chooses to expose, so a request the app makes
 * itself is the one case where the inspector can honestly show a *complete* header set, plus the exact
 * reason phrase, the final URL after redirects and a measured duration.
 *
 * It never touches the request or response bodies: reading them would consume the streams the app
 * needs. Request bodies for this source are therefore reported as unavailable, with that reason, and
 * an app that wants them can log the bytes before handing them to OkHttp.
 *
 * Usage (documented, not wired into this project because no OkHttpClient is created here yet):
 *
 * ```
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(NetworkInspectorInterceptor(inspector))
 *     .build()
 * ```
 *
 * When the inspector is disabled (every release build) the interceptor is a pure pass-through.
 */
class NetworkInspectorInterceptor(
    private val inspector: InspectorRuntime,
    private val tabId: String = APP_CLIENT_TAB_ID,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!inspector.enabled) return chain.proceed(chain.request())

        val request = chain.request()
        val startedAtWallClock = System.currentTimeMillis()
        val startedAtRealtime = SystemClock.elapsedRealtime()

        val observation = RequestObservation(
            tabId = tabId,
            url = request.url.toString(),
            method = request.method,
            observedAtMillis = startedAtWallClock,
            timeSource = EvidenceSource.APP_CLOCK,
            isForMainFrame = false,
            isRedirect = null,
            hasGesture = null,
            headers = flattenHeaders(request.headers),
            headersReport = HeadersReport.APP_HTTP_CLIENT,
            initiator = Initiator.RESOURCE_LOAD,
            observedVia = EvidenceSource.APP_HTTP_CLIENT,
        )

        val response = try {
            chain.proceed(request)
        } catch (io: IOException) {
            dispatchFailure(observation, io)
            throw io
        } catch (other: Throwable) {
            dispatchFailure(observation, other)
            throw other
        }

        try {
            val duration = SystemClock.elapsedRealtime() - startedAtRealtime
            inspector.dispatch(
                InspectorMessage.AppHttpExchange(
                    request = observation,
                    response = ResponseObservation(
                        tabId = tabId,
                        url = request.url.toString(),
                        method = request.method,
                        statusCode = response.code,
                        reasonPhrase = response.message,
                        statusSource = EvidenceSource.APP_HTTP_CLIENT,
                        headers = flattenHeaders(response.headers),
                        headersReport = HeadersReport.APP_HTTP_CLIENT,
                        contentType = response.header("Content-Type"),
                        observedAtMillis = System.currentTimeMillis(),
                        timeSource = EvidenceSource.APP_CLOCK,
                        durationMillis = duration,
                        isForMainFrame = false,
                        observedVia = EvidenceSource.APP_HTTP_CLIENT,
                        finalUrl = response.request.url.toString(),
                    )
                )
            )
        } catch (_: Throwable) {
            // Observing must never break the caller's request.
        }
        return response
    }

    private fun dispatchFailure(observation: RequestObservation, error: Throwable) {
        try {
            inspector.dispatch(
                InspectorMessage.RequestFailed(
                    FailureObservation(
                        tabId = tabId,
                        url = observation.url,
                        description = error.message ?: error.javaClass.simpleName,
                        errorCode = null,
                        isForMainFrame = false,
                        method = observation.method,
                        observedAtMillis = System.currentTimeMillis(),
                    )
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun flattenHeaders(headers: okhttp3.Headers): Map<String, String> {
        if (headers.size == 0) return emptyMap()
        val result = LinkedHashMap<String, String>(headers.size)
        for (index in 0 until headers.size) {
            if (result.size >= InspectorLimits.MAX_HEADERS_PER_MESSAGE) break
            val name = headers.name(index)
            val value = headers.value(index)
            val existing = result[name]
            result[name] = if (existing == null) value else "$existing, $value"
        }
        return result
    }
}
