package run.ostrom.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.cli.BuildInfo
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.doctor.CheckStatus
import run.ostrom.cli.doctor.DoctorCheck
import run.ostrom.cli.doctor.RunDoctorUseCaseImpl

/** `ostrom doctor [--json]`: exit 0 without a problem, 1 with at least one ✗. */
class DoctorCommand(private val dependencies: CliDependencies) : CliktCommand(name = "doctor") {
    private val json by option("--json", help = "Machine-readable output").flag()

    override fun help(context: Context) = "Check everything and say what to do"

    override fun run() {
        val doctor = RunDoctorUseCaseImpl(
            version = BuildInfo.VERSION,
            environment = dependencies.environment,
            files = dependencies.files,
            finder = dependencies.finder,
            configDirectory = dependencies.environment.configDirectory,
            config = dependencies.config,
            api = dependencies.api,
        )
        val checks = runBlocking { doctor() }
        val problems = checks.count { it.status == CheckStatus.PROBLEM }
        val warnings = checks.count { it.status == CheckStatus.WARNING }
        dependencies.console.out(if (json) asJson(checks, problems, warnings) else asText(checks, problems, warnings))
        if (problems > 0) throw ProgramResult(ExitCode.FAILED.code)
    }

    private fun asText(checks: List<DoctorCheck>, problems: Int, warnings: Int): String = buildString {
        checks.forEach { append("  ${it.status.mark} ${it.message}\n") }
        append(
            when {
                problems == 0 && warnings == 0 -> "Everything looks good.\n"
                else -> "${plural(problems, "problem")}, ${plural(warnings, "warning")}.\n"
            },
        )
    }

    private fun asJson(checks: List<DoctorCheck>, problems: Int, warnings: Int): String = JsonObject(
        mapOf(
            "checks" to JsonArray(
                checks.map {
                    JsonObject(mapOf("status" to JsonPrimitive(it.status.name.lowercase()), "message" to JsonPrimitive(it.message)))
                },
            ),
            "problems" to JsonPrimitive(problems),
            "warnings" to JsonPrimitive(warnings),
        ),
    ).toString() + "\n"

    private fun plural(count: Int, word: String) = "$count $word" + if (count == 1) "" else "s"
}
