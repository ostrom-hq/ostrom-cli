// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.launcher.child
import run.ostrom.cli.launcher.list
import run.ostrom.cli.launcher.text

/** `id name v3 cluster/namespace image agent`, tab separated: a row of `ostrom cloud profiles`, as TeamCode's. */
internal fun JsonObject.profileLine(): String {
    val spec = child("spec")
    val where = "${text("clusterName") ?: spec?.text("clusterId") ?: "?"}/${spec?.text("namespace") ?: "?"}"
    val agent = listOfNotNull(spec?.text("agentKind"), spec?.text("agentVersion")).joinToString(" ").ifEmpty { "-" }
    return listOf(
        text("id") ?: "?",
        text("name") ?: "?",
        "v${(this["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: "?"}",
        where,
        spec?.text("image") ?: "-",
        agent,
    ).joinToString("\t")
}

/**
 * `ostrom cloud profiles [--member ID|LOGIN] [--json]`, as TeamCode's `teamcode cloud profiles`: the profiles the
 * caller can create sessions from (every profile for an owner or an admin), or `--member`'s, which an owner or an admin
 * asks for (`?member=`, `runners.cloud.profiles.member.v1`: an older server would list the caller's own instead).
 */
internal class CloudProfilesCommand(private val dependencies: CliDependencies) : CliktCommand(name = "profiles") {
    override fun help(context: Context) =
        "List the profiles you can create sessions from (every profile for an owner or an admin): id, name, " +
            "version, cluster/namespace, image and agent"

    private val member by option("--member", help = "The profiles this member can use (id or login); owners, admins")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        checkMember(member)
        val access = dependencies.cloudAccess(server, org)
        val signed = access.signed
        dependencies.requireFeature(signed.serverUrl, signed.token, RUNNERS_CLOUD_PROFILES, NO_PROFILES)
        if (member != null) {
            val feature = RUNNERS_CLOUD_PROFILES_MEMBER
            dependencies.requireFeature(signed.serverUrl, signed.token, feature, NO_PROFILES_MEMBER)
        }
        val path = member?.let { "$CLOUD_PROFILES?member=${segment(it)}" } ?: CLOUD_PROFILES
        val profiles = access.read(path, "No cloud profiles on ${signed.serverUrl}.")
            .list("profiles").filterIsInstance<JsonObject>()
        val console = dependencies.console
        if (json) {
            console.data(JsonArray(profiles).toString() + "\n")
            return
        }
        profiles.forEach { console.out(it.profileLine() + "\n") }
        if (profiles.isEmpty()) {
            console.err(
                if (member != null) "No profile is available to this member.\n"
                else "No profile is available; ask an admin to assign one.\n",
            )
        }
    }
}
