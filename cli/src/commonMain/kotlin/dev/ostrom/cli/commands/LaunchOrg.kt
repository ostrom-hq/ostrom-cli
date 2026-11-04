// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.launch.ApiServerTransport
import dev.ostrom.cli.launcher.ServerJson
import dev.ostrom.cli.platform.ConfigKey
import dev.ostrom.cli.platform.ConfigStoreImpl

/** The organisation named: [wanted] (`--org`), else `OSTROM_ORG`, else the one `ostrom init` kept; null if none. */
internal fun CliDependencies.keptOrg(wanted: String?): String? = wanted?.takeIf { it.isNotEmpty() }
    ?: environment["OSTROM_ORG"]?.takeIf { it.isNotEmpty() }
    ?: ConfigStoreImpl(files, configFile(directory())).get(ConfigKey.ORG)

/**
 * The id of the organisation `ostrom <agent>` acts in, sent in `Ostrom-Org` (the contract's id, never a short name):
 * the one [keptOrg] names, looked up among the caller's; null when none is named, and the server then takes the
 * person's only organisation. One named that the person is not a member of stops the launch (66).
 */
internal fun CliDependencies.launchOrg(serverUrl: String, token: String, wanted: String?): String? {
    val kept = keptOrg(wanted) ?: return null
    val orgs = memberOrgs(ServerJson(ApiServerTransport(api(serverUrl))), serverUrl, token)
    keepOrgs(serverUrl, orgs)
    return named(orgs, kept).id
}
