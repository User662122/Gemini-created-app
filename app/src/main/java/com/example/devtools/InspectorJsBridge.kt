package com.example.devtools

import android.webkit.JavascriptInterface

/**
 * The one object exposed to page JavaScript (`NetworkInspectorBridge`).
 *
 * Security posture: the bridge accepts a single method whose only effect is to hand a JSON string to
 * the capture pipeline. It cannot read app data, cannot run commands, cannot touch the file system and
 * cannot change the page. The worst a hostile page can do is produce log noise, and that noise is
 * rate-limited and counted rather than trusted:
 *
 *  * payloads larger than [InspectorLimits.MAX_BRIDGE_BATCH_CHARS] are ignored;
 *  * batches are capped per second by the observer;
 *  * every record is validated and length-clipped by [PageJsRecordParser];
 *  * values from the page are marked with [EvidenceSource.PAGE_JAVASCRIPT], never mixed silently with
 *    data WebView reported.
 *
 * The interface is only added to a WebView when the inspector is enabled in a debug build, and it is
 * removed when the WebView is destroyed.
 */
class InspectorJsBridge(
    private val onBatch: (String) -> Unit,
) {

    @JavascriptInterface
    fun pushBatch(payload: String?) {
        val text = payload ?: return
        if (text.length > InspectorLimits.MAX_BRIDGE_BATCH_CHARS) return
        if (text.isEmpty() || text[0] != '{') return
        onBatch(text)
    }
}
