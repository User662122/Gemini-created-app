package com.example.devtools

/**
 * Recognises a rejection by the site's own access-control layer (a CDN bot- or rate-rule), as opposed
 * to a network fault, a TLS problem or an error in the page.
 *
 * The evidence used is only what the server itself put on the response: the reference header and the
 * edge-node id these layers stamp on every rejection, plus the server name they answer under. Nothing
 * here identifies, imitates or works around the rule — it *names* it, so a blocked session can be
 * told apart from a broken one and the reference can be quoted to the site's owner.
 *
 * Why this distinction matters at all: such rules are frequently armed or tightened only while the
 * site is at peak load, and they key on client properties that differ between browsers. A session
 * that works at 00:48 and is refused at 08:00 from the same device and address is not a network
 * fault; saying so, with the server's own reference, is the whole job of this object.
 */
object AccessControlRejection {

    /** Header carrying the rejection's reference, e.g. `18.b60e0317.1791437529.de1b38a3`. */
    private const val REFERENCE_HEADER = "x-reference-error"

    /** Header carrying the edge node's id for the same request. */
    private const val EDGE_NODE_HEADER = "akamai-grn"

    /** `Server:` values these layers answer under. */
    private val SERVER_MARKERS = listOf("akamaighost", "edgesuite")

    /** Statuses an access-control layer refuses with. */
    private val REJECTION_STATUSES = setOf(403, 405, 429)

    /**
     * True when [headers] carry an access-control layer's own rejection marks on [statusCode].
     *
     * The status alone is not enough: an application can return 403 for its own reasons, and calling
     * that a CDN rejection would be a guess dressed up as a diagnosis.
     */
    fun isRejection(statusCode: Int, headers: Map<String, String>): Boolean {
        if (statusCode !in REJECTION_STATUSES) return false
        val lowered = lowerHeaders(headers)
        if (REFERENCE_HEADER in lowered || EDGE_NODE_HEADER in lowered) return true
        val server = lowered["server"] ?: return false
        return SERVER_MARKERS.any { server.contains(it, ignoreCase = true) }
    }

    /**
     * The reference the site's support can look up, or null when the server sent none.
     *
     * Quoting it is the only productive response to a rejection like this: it names the exact rule
     * and edge node that refused the request, which neither the client nor a third party can see.
     */
    fun referenceId(headers: Map<String, String>): String? {
        val lowered = lowerHeaders(headers)
        return lowered[REFERENCE_HEADER]?.takeIf { it.isNotBlank() }
            ?: lowered[EDGE_NODE_HEADER]?.takeIf { it.isNotBlank() }
    }

    private fun lowerHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>(headers.size)
        for ((name, value) in headers) result[name.lowercase()] = value
        return result
    }
}
