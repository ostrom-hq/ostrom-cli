// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.hooks.runtime

import dev.ostrom.cli.hooks.gate.GateCatalogue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The gate catalogue of the session: the copy laid down at launch, kept never older than the TTL when an action is
 * matched against it, so that an action a gate owner declares mid-session is seen without a call on every tool use
 * (prototype `catalogue`, SUITES 28/10).
 *
 * Within the TTL the copy is served; past a sixth of it, a detached `ostrom hook gates-refresh` rewrites it (one at a
 * time). Past the TTL, or with no copy, the hook refreshes in line. Serving a copy older than the TTL is unsafe in one
 * direction: an action a gate was added for would run without asking.
 */
class CatalogueStore(
    private val context: HookContext,
    private val files: HookFiles,
    private val calls: OstromCalls,
    private val background: BackgroundRefresh,
) {
    /** The catalogue to match an action against now. */
    fun current(): GateCatalogue {
        val path = context.gatesFile
        val local = path?.let { files.read(it) }?.let { GateCatalogue.parse(it) }
        val age = path?.let { files.ageMillis(it) } ?: Long.MAX_VALUE
        if (local != null && path != null && age < context.gatesTtlMillis) {
            if (age >= context.gatesTtlMillis / REFRESH_FRACTION) refreshInBackground(path)
            return local
        }
        return refresh(INLINE_TIMEOUT_MILLIS).first
    }

    /**
     * Asks Ostrom for the catalogue and keeps it. On failure: the local copy, else no action.
     *
     * @return The catalogue, and whether it came from Ostrom.
     */
    fun refresh(timeoutMillis: Int): Pair<GateCatalogue, Boolean> {
        val path = context.gatesFile
        val local = path?.let { files.read(it) }
        val answer = calls.call(Routes.catalog(context.repository), null, timeoutMillis)
        val actions = (answer as? CallResult.Ok)?.body?.get("actions")
        if (actions != null) {
            val merged = parseObject(local) + ("actions" to actions)
            val text = JsonObject(merged).toString()
            val catalogue = GateCatalogue.parse(text)
            if (catalogue != null) {
                if (path != null) files.write(path, text)
                return catalogue to true
            }
        }
        val kept = local?.let { GateCatalogue.parse(it) }
        return (kept ?: GateCatalogue()) to false
    }

    /** `ostrom hook gates-refresh`: the background half of [current], no one waits for it. */
    fun refreshNow() {
        val path = context.gatesFile ?: return
        // A refresh that failed leaves its marker: no new attempt before it is stale.
        if (refresh(BACKGROUND_TIMEOUT_MILLIS).second) files.delete(marker(path))
    }

    private fun refreshInBackground(path: String) {
        val marker = marker(path)
        val busy = files.ageMillis(marker)?.let { it < MARKER_STALE_MILLIS } ?: false
        if (busy || !files.write(marker, "refreshing")) return
        if (!background.start()) files.delete(marker)
    }

    private fun marker(path: String) = "$path.refreshing"

    private fun parseObject(text: String?): Map<String, kotlinx.serialization.json.JsonElement> = try {
        text?.let { Json.parseToJsonElement(it) as? JsonObject }.orEmpty()
    } catch (_: IllegalArgumentException) {
        emptyMap()
    }

    private companion object {
        const val REFRESH_FRACTION = 6
        const val INLINE_TIMEOUT_MILLIS = 800
        const val BACKGROUND_TIMEOUT_MILLIS = 5_000
        const val MARKER_STALE_MILLIS = 10_000L
    }
}

/** Percent-encoding of query values (RFC 3986 unreserved characters kept). */
object Urls {
    /** [value] encoded for a URL query or path segment. */
    fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and BYTE
            if (c.toChar().isLetterOrDigit() && c < ASCII || c.toChar() in "-_.~") {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr NIBBLE]).append(HEX[c and LOW])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
    private const val BYTE = 0xff
    private const val ASCII = 0x80
    private const val NIBBLE = 4
    private const val LOW = 0x0f
}
