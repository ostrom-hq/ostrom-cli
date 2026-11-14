// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.launcher

/**
 * The labels of a launch (`--label key=value`, TeamCode's): the rules of the server, said before the launch is asked
 * so that a mistake is a usage error rather than a refused call.
 */
object Labels {
    /** At most this many labels on a launch. */
    const val MAX = 16

    private const val MAX_VALUE = 256
    private val KEY = Regex("[a-z0-9][a-z0-9._/-]{0,62}")

    /** One `key=value` (split at the first `=`) as a pair, or the reason it is not a label. */
    fun parse(text: String): Result<Pair<String, String>> {
        val at = text.indexOf('=')
        if (at < 0) return Result.failure(IllegalArgumentException("a label is key=value: \"$text\""))
        val key = text.substring(0, at)
        val value = text.substring(at + 1)
        return when {
            !KEY.matches(key) ->
                Result.failure(IllegalArgumentException("label key \"$key\": lower case letters, digits, . _ / -"))
            value.length > MAX_VALUE ->
                Result.failure(IllegalArgumentException("label \"$key\": a value is at most $MAX_VALUE characters"))
            else -> Result.success(key to value)
        }
    }

    /** All of [texts] as labels (a repeated key keeps the last value), or the first reason one is not. */
    fun parseAll(texts: List<String>): Result<Map<String, String>> {
        val labels = linkedMapOf<String, String>()
        for (text in texts) {
            val (key, value) = parse(text).getOrElse { return Result.failure(it) }
            labels[key] = value
        }
        return if (labels.size > MAX) {
            Result.failure(IllegalArgumentException("at most $MAX labels"))
        } else {
            Result.success(labels)
        }
    }
}
