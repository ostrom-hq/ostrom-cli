// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.login

import dev.ostrom.cli.launcher.Credentials
import dev.ostrom.cli.launcher.ServerAnswer
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** How a sign-in ended. */
sealed interface LoginOutcome {
    /** Signed in. @property credentials The device's credentials. */
    data class SignedIn(val credentials: Credentials) : LoginOutcome

    /**
     * Not signed in.
     *
     * @property exitCode The exit code of the spec (CLI.md § 2.2, `ostrom login`).
     * @property message What to tell the developer.
     */
    data class Stopped(val exitCode: Int, val message: String) : LoginOutcome
}

/**
 * `ostrom login`: the device flow of Ostrom's GitHub app, through the server (`POST /api/v1/auth/device`, then
 * `POST /api/v1/auth/device/token` every `interval` seconds until the developer said yes on GitHub).
 *
 * The page the developer is sent to must be GitHub's (security audit S2-35): a server that is not Ostrom cannot send
 * them to a look-alike page to collect their code.
 *
 * @param githubWeb GitHub's web address (`https://github.com`, or `OSTROM_GITHUB_WEB_URL`), without a trailing slash.
 * @param sleep Waits the given milliseconds (virtual in tests).
 * @param say Prints a line for the developer.
 */
class DeviceLogin(
    private val server: ServerJson,
    private val githubWeb: String,
    private val sleep: suspend (Long) -> Unit,
    private val say: (String) -> Unit,
) {
    /** Signs in on [serverUrl]. */
    suspend fun signIn(serverUrl: String): LoginOutcome = when (val start = server.post(START, objectOf(), null)) {
        is ServerAnswer.Failed -> LoginOutcome.Stopped(codeOf(start), messageOf(start, serverUrl))
        is ServerAnswer.Ok -> started(start.body, serverUrl)
    }

    /** Shows the code of a started flow, then waits for GitHub's yes. */
    private suspend fun started(body: JsonObject, serverUrl: String): LoginOutcome {
        val deviceCode = body.text("deviceCode").orEmpty()
        val userCode = body.text("userCode").orEmpty()
        val page = body.text("verificationUri") ?: "$githubWeb/login/device"
        // the code and the page go to the terminal: nothing but GitHub's page, and no control character in either
        val refusal = when {
            !page.startsWith("$githubWeb/") || page.any(::isControl) ->
                "$serverUrl sent a sign-in page that is not GitHub's (${page.filterNot(::isControl)}). " +
                    "Check the --server URL."
            deviceCode.isEmpty() || !USER_CODE.matches(userCode) ->
                "$serverUrl answered without a usable sign-in code. Check the --server URL."
            else -> null
        }
        if (refusal != null) return LoginOutcome.Stopped(UNREACHABLE, refusal)
        say("! Copy your one-time code: $userCode")
        say("  Open $page and enter it.")
        // a server's interval of 0 would ask GitHub in a tight loop, and a huge expiry would never end
        val interval = ((body["interval"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_INTERVAL_SECONDS)
            .coerceAtLeast(1).coerceAtMost(MAX_INTERVAL_SECONDS)
        val expiresIn = ((body["expiresIn"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_EXPIRY_SECONDS)
            .coerceAtLeast(1).coerceAtMost(MAX_EXPIRY_SECONDS)
        return poll(deviceCode, interval * MILLIS, expiresIn * MILLIS, serverUrl)
    }

    /**
     * Asks until GitHub's answer, the code's expiry, or a refusal. A lost connection, a 5xx or a 429 is asked again
     * (the developer may already have entered the code); still lost at the expiry, it is what is said.
     */
    private suspend fun poll(deviceCode: String, every: Long, budget: Long, serverUrl: String): LoginOutcome {
        var waited = 0L
        var pause = every
        var lost: ServerAnswer.Failed? = null
        var outcome: LoginOutcome? = null
        while (outcome == null) {
            if (waited >= budget) {
                outcome = lost?.let { LoginOutcome.Stopped(UNREACHABLE, messageOf(it, serverUrl)) }
                    ?: LoginOutcome.Stopped(EXPIRED, "The code expired. Run ostrom login again.")
            } else {
                sleep(pause)
                waited += pause
                val answer = server.post(TOKEN, objectOf("deviceCode" to deviceCode), null)
                lost = (answer as? ServerAnswer.Failed)?.takeIf(::isTransient)
                outcome = if (lost == null) outcomeOf(answer, serverUrl) else null
                if (answer is ServerAnswer.Failed && answer.type == "slow_down") pause += SLOW_DOWN_MILLIS
            }
        }
        return outcome
    }

    private fun isTransient(answer: ServerAnswer.Failed): Boolean =
        answer.status.let { it == null || it >= SERVER_ERROR || it == TOO_MANY }

    private fun isControl(char: Char): Boolean = char < ' ' || char == '\u007f'

    /** The outcome of one poll, or null to poll again. */
    private fun outcomeOf(answer: ServerAnswer, serverUrl: String): LoginOutcome? = when (answer) {
        is ServerAnswer.Ok -> signedIn(answer)
        is ServerAnswer.Failed -> refusal(answer, serverUrl)
    }

    private fun refusal(answer: ServerAnswer.Failed, serverUrl: String): LoginOutcome? = when {
        answer.status == PENDING || answer.type in WAIT -> null
        answer.type == "expired_token" -> LoginOutcome.Stopped(EXPIRED, "The code expired. Run ostrom login again.")
        answer.type == "access_denied" -> LoginOutcome.Stopped(CANCELLED, "GitHub sign-in was cancelled.")
        else -> LoginOutcome.Stopped(codeOf(answer), messageOf(answer, serverUrl))
    }

    private fun signedIn(answer: ServerAnswer.Ok): LoginOutcome {
        val token = answer.body.text("token")
        val login = answer.body.text("login")
        return if (token.isNullOrEmpty() || login.isNullOrEmpty()) {
            LoginOutcome.Stopped(UNREACHABLE, "Ostrom answered without a token. Try again later.")
        } else {
            LoginOutcome.SignedIn(Credentials(token, login))
        }
    }

    private fun codeOf(answer: ServerAnswer.Failed): Int = if (answer.status == FORBIDDEN) REFUSED else UNREACHABLE

    private fun messageOf(answer: ServerAnswer.Failed, serverUrl: String): String = when (answer.status) {
        null -> "Ostrom is unreachable at $serverUrl. Check your network, or the --server URL."
        FORBIDDEN -> answer.message
        else -> "Ostrom could not sign you in: ${answer.message}"
    }

    /** The device flow's waits and limits. */
    companion object {
        private const val START = "/api/v1/auth/device"
        private const val TOKEN = "/api/v1/auth/device/token"
        private const val MILLIS = 1_000L
        private const val DEFAULT_INTERVAL_SECONDS = 5
        private const val DEFAULT_EXPIRY_SECONDS = 900
        private const val SLOW_DOWN_MILLIS = 5_000L
        private const val MAX_INTERVAL_SECONDS = 60
        private const val MAX_EXPIRY_SECONDS = 1_800
        private const val SERVER_ERROR = 500
        private const val TOO_MANY = 429
        private val USER_CODE = Regex("[A-Za-z0-9-]{1,32}")
        // an Int? like the answer's status: a lost connection (no status) never gets here, so no null test either
        @Suppress("MayBeConst") // a const cannot be an Int?, and an Int would bring the null test back
        private val PENDING: Int? = 428
        private const val FORBIDDEN = 403
        private const val EXPIRED = 1
        private const val CANCELLED = 2
        private const val UNREACHABLE = 69
        private const val REFUSED = 77
        private val WAIT = setOf("authorization_pending", "slow_down")
    }
}
