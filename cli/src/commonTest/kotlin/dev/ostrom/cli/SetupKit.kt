// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

import dev.ostrom.cli.imports.FakeMysql
import dev.ostrom.cli.platform.CaptureResult
import dev.ostrom.cli.platform.ProcessCapture
import kotlin.test.assertTrue

/** What the tests of `init`, `harness`, `invite`, `members` and `policy` share: a signed-in device, an organisation. */
open class SetupKit : CliKit() {
    protected val acme = """{"id":"o1","slug":"acme","name":"Acme","role":"admin","createdAt":"2026-10-30T00:00:00Z"}"""
    protected val beta = """{"id":"o2","slug":"beta","name":"Beta","role":"member"}"""

    /** The environment of an agent's session. */
    protected val inSession = withServer + ("OSTROM_LAUNCH_ID" to "L")

    /** `GET /api/v1/orgs` answering [views]. */
    protected fun orgs(vararg views: String) =
        "GET /api/v1/orgs" to (200 to """{"items":[${views.joinToString(",")}]}""")

    /** git in a repository: `rev-parse --show-toplevel` prints [top], `remote get-url origin` answers [origin]. */
    protected fun gitTop(
        top: String = "/work/acme-api",
        origin: CaptureResult = CaptureResult.Exited(2, "error: No such remote 'origin'\n"),
    ): ProcessCapture = object : ProcessCapture {
        override fun capture(command: List<String>, timeoutMillis: Int) =
            if ("get-url" in command) origin else CaptureResult.Exited(0, "$top\n")

        override fun detach(command: List<String>) = false
    }

    /**
     * Runs `ostrom args` signed in (credentials in [home]) against a server answering [routes], with Acme as the only
     * organisation unless [routes] says otherwise.
     */
    @Suppress("LongParameterList") // one default per thing a test changes
    protected fun run(
        routes: Map<String, Pair<Int, String>>,
        vararg args: String,
        environment: Map<String, String> = withServer,
        capture: ProcessCapture = FakeMysql(),
        installed: Set<String> = setOf("git", "claude"),
        home: String = "/home/maya/.config/ostrom",
    ): Int {
        signIn(home)
        val server = serverOf(mapOf(orgs(acme)) + routes)
        val command = cli(environment = environment, api = server, capture = capture, installed = installed)
        return command.run(arrayOf(*args))
    }

    /** The requests the commands sent, as `METHOD path`, the organisation lookup left out. */
    protected fun sent(): List<String> =
        requests.map { "${it.second.method} ${it.second.path}" }.filter { it != "GET /api/v1/orgs" }

    /** The body of the last request to [route] (`METHOD path`). */
    protected fun bodyOf(route: String): String? =
        requests.lastOrNull { "${it.second.method} ${it.second.path}" == route }?.second?.body

    /** Asserts that stderr contains [text], showing all of it otherwise. */
    protected fun assertErr(text: String) = assertTrue(console.err.contains(text), console.err.toString())
}
