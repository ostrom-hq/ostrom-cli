// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.doctor

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The routes the CLI calls, checked against ostrom-core's API contract: the checkout the build uses, at the commit of
 * `gradle/ostrom-core.ref` in CI.
 */
class ContractRoutesTest {
    @Test
    fun theDoctorProbesAGetRouteOfTheContract() {
        assertTrue(HEALTH_ROUTE in getRoutes(), "$HEALTH_ROUTE is not a GET route of $contract")
    }

    private val contract = File(
        checkNotNull(System.getProperty(CONTRACT)) { "$CONTRACT is set by cli/build.gradle.kts" },
    )

    /** The paths of the contract that have a `get` operation: `  /path:` then, nested under it, `    get:`. */
    private fun getRoutes(): Set<String> {
        assertTrue(contract.isFile, "the contract is read from the ostrom-core checkout next to this one: $contract")
        val lines = contract.readLines().dropWhile { it != "paths:" }.drop(1).takeWhile { it.isEmpty() || it[0] == ' ' }
        val routes = mutableSetOf<String>()
        var path: String? = null
        for (line in lines) {
            when {
                PATH.matches(line) -> path = line.trim().removeSuffix(":")
                line == "    get:" -> path?.let(routes::add)
            }
        }
        assertTrue(routes.isNotEmpty(), "no GET route read under `paths:` of $contract: has its layout changed?")
        return routes
    }

    private companion object {
        const val CONTRACT = "ostrom.contract"
        val PATH = Regex("""^ {2}/\S*:$""")
    }
}
