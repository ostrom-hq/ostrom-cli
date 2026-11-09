// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.hooks.runtime

/**
 * Masks what looks like a secret in a command the hooks report after the fact (a refusal made on the workstation,
 * audit A6): a bearer token, a password in a URL, a `*_TOKEN=` variable, a token flag, a provider's or Ostrom's key.
 * The same masks as the server's (ostrom-server `SecretMasks`), which masks again whatever arrives.
 */
object CommandSecrets {
    /** What replaces a secret. */
    const val MASK: String = "***"

    /**
     * Masks a command.
     *
     * @param text The command.
     *
     * @return The command, each secret replaced by [MASK].
     */
    fun masked(text: String): String = SECRETS.fold(text) { masked, (pattern, replacement) ->
        pattern.replace(masked, replacement)
    }

    private val SECRETS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{8,}") to "$1 $MASK",
        Regex("://[^/\\s:@]+(:[^/\\s@]*)?@") to "://$MASK@",
        Regex("(?i)\\b([A-Z0-9_]*(TOKEN|SECRET|PASSWORD|PASSWD|API_?KEY|ACCESS_?KEY)[A-Z0-9_]*)=\\S+") to "$1=$MASK",
        Regex("(?i)(--?(token|password|passwd|secret|api-key|apikey)[= ])\\S+") to "$1$MASK",
        Regex(
            "\\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{16,}" +
                "|xox[abprs]-[A-Za-z0-9-]{10,}|os[acp]_[0-9a-f]{16,}|AKIA[0-9A-Z]{16})\\b",
        ) to MASK,
    )
}
