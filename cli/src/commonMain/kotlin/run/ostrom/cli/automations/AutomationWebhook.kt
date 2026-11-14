// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.automations

import run.ostrom.cli.launcher.text
import run.ostrom.cli.workflows.number
import kotlinx.serialization.json.JsonObject

/**
 * How to call a webhook automation, in the signed mode, the one Ostrom serves: the address (the plain one, which
 * follows the automation, or [urlVersion] `current` / `vN`, which answers only while the definition is at that
 * version), the headers to sign with and, once, the [secret].
 *
 * @param serverUrl Prepended to an address the server gave relative.
 */
internal fun JsonObject.webhookAddress(urlVersion: String, secret: String?, serverUrl: String): List<String> {
    val url = text("webhookUrl") ?: return emptyList()
    val absolute = if (url.startsWith("/")) serverUrl.trimEnd('/') + url else url
    val pinned = when (urlVersion) {
        LATEST -> null
        CURRENT -> number("version")
        else -> urlVersion.removePrefix("v")
    }
    val address = if (pinned == null) absolute else absolute.removeSuffix("/trigger") + "/v/$pinned/trigger"
    return buildList {
        add("Webhook (signed): POST $address")
        add("  Ostrom-Timestamp: the time, in Unix seconds (at most five minutes old)")
        add("  Ostrom-Signature: v1=<hex>, the HMAC-SHA256 of \"<timestamp>.<raw body>\" keyed with the whole secret")
        add("  Idempotency-Key (or Ostrom-Delivery): 1 to 128 of A-Z a-z 0-9 . _ : - ; a delivery is accepted once")
        add("  Body: a JSON object of at most 64 KiB")
        if (pinned != null) add("This address runs only while the automation is at version $pinned.")
        if (secret != null) add("Secret (shown once, keep it): $secret")
    }
}

/** What `--url-version` takes: `latest`, `current` or `vN`; null when [text] is none of them. */
internal fun urlVersionOf(text: String): String? = text.takeIf { it == LATEST || it == CURRENT || VERSION.matches(it) }

internal const val LATEST = "latest"
internal const val CURRENT = "current"
private val VERSION = Regex("v[1-9][0-9]{0,8}")

