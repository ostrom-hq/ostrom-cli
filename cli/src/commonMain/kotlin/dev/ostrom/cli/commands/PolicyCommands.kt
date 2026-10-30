// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import dev.ostrom.cli.CliDependencies
import dev.ostrom.cli.ExitCode
import dev.ostrom.cli.launcher.Agent
import dev.ostrom.cli.launcher.child
import dev.ostrom.cli.launcher.flag
import dev.ostrom.cli.launcher.list
import dev.ostrom.cli.launcher.objectOf
import dev.ostrom.cli.launcher.ostromName
import dev.ostrom.cli.launcher.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `GET`/`PUT`: the models the organisation allows, and the default (served). */
private const val MODELS = "/api/v1/policy/models"

/** `GET`/`PUT`: who may approve their own request (SRV-REGLES). */
private const val GATE_POLICY = "/api/v1/gates/policy"

/** `GET ?agent&dev&model`: may this developer start this agent (SRV-LANCEUR, the route `ostrom claude` reads). */
private const val AGENTS = "/api/v1/policy/agents"

/** The values of the self-approval policy (`setGatePolicy`). */
private val SELF_APPROVAL = listOf("sole-approver", "never")

private fun JsonObject.texts(key: String) = list(key).filterIsInstance<JsonPrimitive>().map { it.content }

/** `ostrom policy`: models, self-approval, rules; and whether a developer may start an agent. */
internal class PolicyCommand :
    GroupCommand("policy", "Models, self-approval and rules of your organisation (admin)", comingSoon = false)

/** `ostrom policy show`: the model policy and the self-approval policy. */
internal class PolicyShowCommand(private val dependencies: CliDependencies) : CliktCommand(name = "show") {
    override fun help(context: Context) = "Show the policy"

    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val access = dependencies.orgAccess(server, org)
        val models = access.read(MODELS, "No model policy in ${access.org.slug}.")
        // the gate policy is served by SRV-REGLES: a server without it still shows the models
        val gates = access.readIfServed(GATE_POLICY)
        val console = dependencies.console
        if (json) {
            console.out("${objectOf("models" to models, "gates" to gates)}\n")
            return
        }
        val allowed = models.texts("allowed")
        console.out("Policy of ${access.org.name}\n")
        val list = if (allowed.isEmpty()) "none allowed" else allowed.joinToString(", ")
        console.out("  Models         $list · default ${models.text("default") ?: "none"}\n")
        val self = gates?.text("selfApproval") ?: "not on this server yet"
        console.out("  Self-approval  $self\n")
        console.out("Change it: ostrom policy set models|default-model|self-approval <value>\n")
    }
}

/**
 * `ostrom policy set <key> <value>`: `models` (comma-separated), `default-model`, `self-approval` (`sole-approver`
 * or `never`). The model policy is read first, so that what is not set is kept as it is.
 */
internal class PolicySetCommand(private val dependencies: CliDependencies) : CliktCommand(name = "set") {
    override fun help(context: Context) = "Set models, default-model or self-approval"

    private val key by argument("key")
    private val value by argument("value")
    private val org by orgOption()
    private val server by serverFlag()

    override fun run() {
        dependencies.outsideSessions("policy set")
        if (key !in KEYS) throw stopWith(ExitCode.USAGE, "Unknown key \"$key\". Keys: ${KEYS.joinToString(", ")}.")
        if (key == SELF && value !in SELF_APPROVAL) {
            throw stopWith(ExitCode.USAGE, "self-approval must be ${SELF_APPROVAL.joinToString(" or ")}.")
        }
        val access = dependencies.orgAccess(server, org)
        if (key == SELF) {
            access.write("PUT", GATE_POLICY, objectOf("selfApproval" to value), "No gate policy here.")
        } else {
            access.write("PUT", MODELS, models(access), "No model policy in ${access.org.slug}.")
        }
        dependencies.console.out("✓ $key = $value in ${access.org.name}\n")
    }

    /** The model policy with [value] set, the default kept when it is still allowed, the members' lists kept. */
    private fun models(access: OrgAccess): JsonObject {
        val current = access.read(MODELS, "No model policy in ${access.org.slug}.")
        val allowed = if (key == MODELS_KEY) value.split(',').map(String::trim).filter(String::isNotEmpty) else
            current.texts("allowed")
        if (allowed.isEmpty()) throw stopWith(ExitCode.USAGE, "Name at least one model.")
        val wanted = if (key == MODELS_KEY) current.text("default") else value
        if (key == DEFAULT && wanted !in allowed) {
            throw stopWith(ExitCode.USAGE, "$value is not an allowed model (allowed: ${allowed.joinToString(", ")}).")
        }
        val default = wanted?.takeIf { it in allowed } ?: allowed.first()
        return objectOf("allowed" to allowed, "default" to default, "byMember" to current.child("byMember"))
    }

    private companion object {
        const val MODELS_KEY = "models"
        const val DEFAULT = "default-model"
        const val SELF = "self-approval"
        val KEYS = listOf(MODELS_KEY, DEFAULT, SELF)
    }
}

/** `ostrom policy check <agent> [--model M] [--dev LOGIN]`: what `ostrom <agent>` would be told before it starts. */
internal class PolicyCheckCommand(private val dependencies: CliDependencies) : CliktCommand(name = "check") {
    override fun help(context: Context) = "May a developer start this agent (and model)"

    private val agent by argument("agent")
    private val model by option("--model", help = "The model the agent would use")
    private val dev by option("--dev", help = "A developer's login (default: you)")
    private val org by orgOption()
    private val server by serverFlag()
    private val json by jsonFlag()

    override fun run() {
        val known = Agent.of(agent) ?: throw stopWith(
            ExitCode.USAGE,
            "Unknown agent \"$agent\". Agents: ${Agent.entries.joinToString(", ") { it.command }}.",
        )
        val access = dependencies.orgAccess(server, org)
        val who = dev?.removePrefix("@") ?: access.signed.login
        val query = "agent=${segment(ostromName(known))}&dev=${segment(who)}" +
            model?.let { "&model=${segment(it)}" }.orEmpty()
        val body = access.read("$AGENTS?$query", "No agent policy in ${access.org.slug}.")
        val allowed = body.flag("allowed") == true
        if (json) dependencies.console.out("$body\n")
        val with = model?.let { " with $it" }.orEmpty()
        if (!allowed) {
            throw stopWith(ExitCode.REFUSED, "✗ @$who may not start ${known.displayName}$with: " +
                (body.text("reason") ?: "not allowed"))
        }
        if (!json) {
            dependencies.console.out("✓ @$who may start ${known.displayName}$with in ${access.org.name}.\n")
            body.text("note")?.let { dependencies.console.out("  $it\n") }
        }
    }
}
