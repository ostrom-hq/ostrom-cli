// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.api.OstromApi
import run.ostrom.cli.api.ServerRequest
import run.ostrom.cli.api.ServerResponse
import run.ostrom.cli.api.ServerUnreachableException
import run.ostrom.cli.launcher.Agent
import run.ostrom.cli.launcher.LaunchRequest
import run.ostrom.cli.launcher.SESSIONS_LABELS
import run.ostrom.core.api.models.ServerFeatures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom run <agent> --label key=value` and `ostrom sessions list --label`, as TeamCode's. */
class RunLabelOptionTest : CliKit() {
    @Test
    fun theLauncherSFeatureKeyIsTheContractsKey() {
        assertEquals(ServerFeatures.SESSIONS_LABELS, SESSIONS_LABELS)
    }

    @Test
    fun aLaunchCarriesItsLabels() {
        signIn("/home/maya/.config/ostrom")
        val arguments = arrayOf("run", "claude", "--label", "team=core", "--label", "ticket=ENG-4=2")
        assertEquals(7, cli(environment = withServer).run(arguments))
        val labels = mapOf("team" to "core", "ticket" to "ENG-4=2")
        assertEquals(LaunchRequest(Agent.CLAUDE, emptyList(), null, null, labels = labels), launches.single().first)
    }

    @Test
    fun aBadLabelAndALabelOnACloudSessionAreUsageErrors() {
        signIn("/home/maya/.config/ostrom")
        for (bad in listOf("novalue", "UPPER=1", "k=" + "v".repeat(257))) {
            assertEquals(64, cli(environment = withServer).run(arrayOf("run", "claude", "--label", bad)), bad)
        }
        val many = (1..17).flatMap { listOf("--label", "k$it=v") }.toTypedArray()
        assertEquals(64, cli(environment = withServer).run(arrayOf("run", "claude") + many))
        assertTrue(console.err.contains("at most 16 labels"), console.err.toString())
        assertEquals(64, cli(environment = withServer).run(arrayOf("run", "claude", "--cloud", "--label", "a=b")))
        assertTrue(console.err.contains("--label is for a launch on this machine"), console.err.toString())
        assertEquals(0, launches.size, "nothing launched")
    }

    @Test
    fun theListFiltersByLabelsOnAServerThatKeepsThem() {
        signIn("/home/maya/.config/ostrom")
        now = 0
        val asked = "GET /api/v1/sessions?dev=maya&label=team%3Dcore&label=env%3Dci"
        val items = """{"items":[{"id":"s1","agent":"claude","startedAt":"1970-01-01T00:00:00Z",""" +
            """"labels":{"team":"core","env":"ci"}},{"labels":{"odd":{}}}]}"""
        val routes = mapOf(asked to (200 to items), capabilities(ServerFeatures.SESSIONS_LABELS))
        val arguments = arrayOf("sessions", "list", "--label", "team=core", "--label", "env=ci")
        assertEquals(0, cli(environment = withServer, api = serverOf(routes)).run(arguments))
        val lines = "s1  claude  -  running  0s  env=ci  team=core\n?  -  -  running  ?  odd=\n"
        assertEquals(lines, console.out.toString())
    }

    /** [routes] served, except [broken], which is unreachable. */
    private fun breaking(routes: Map<String, Pair<Int, String>>, broken: String): (String) -> OstromApi = { url ->
        val served = serverOf(routes)(url)
        object : OstromApi {
            override suspend fun get(path: String) = send(ServerRequest("GET", path))
            override suspend fun send(request: ServerRequest): ServerResponse {
                if (request.path.startsWith(broken)) throw ServerUnreachableException(url, Exception("refused"))
                return served.send(request)
            }
        }
    }

    @Test
    fun theLabelledListCarriesEveryOptionAndASessionlessServerIsRefused() {
        signIn("/home/maya/.config/ostrom")
        val asked = "GET /api/v1/sessions?repo=a%2Fb&open=true&before=s9&label=x%3Dy"
        val routes = mapOf(asked to (200 to """{"items":[]}"""), capabilities(ServerFeatures.SESSIONS_LABELS))
        val arguments = arrayOf(
            "sessions", "list", "--all", "--repo", "a/b", "--open", "--before", "s9", "--label", "x=y",
        )
        assertEquals(0, cli(environment = withServer, api = serverOf(routes)).run(arguments))
        val missing = mapOf(capabilities(ServerFeatures.SESSIONS_LABELS))
        assertEquals(69, cli(environment = withServer, api = serverOf(missing)).run(arguments))
        val down = breaking(routes, "/api/v1/sessions")
        assertEquals(69, cli(environment = withServer, api = down).run(arguments))
        val failing = missing + (asked to (500 to "{}"))
        assertTrue(cli(environment = withServer, api = serverOf(failing)).run(arguments) != 0)
    }

    @Test
    fun theListSaysSoToAServerThatKeepsNoLabelsAndRefusesABadOne() {
        signIn("/home/maya/.config/ostrom")
        val routes = mapOf(capabilities())
        val arguments = arrayOf("sessions", "list", "--label", "team=core")
        assertEquals(69, cli(environment = withServer, api = serverOf(routes)).run(arguments))
        assertTrue(console.err.contains("keeps no session labels"), console.err.toString())
        assertEquals(64, cli(environment = withServer).run(arrayOf("sessions", "list", "--label", "x")))
    }
}
