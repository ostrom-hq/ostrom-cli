package dev.ostrom.cli.telemetry

import dev.ostrom.cli.platform.ConfigKey
import dev.ostrom.cli.platform.ConfigStoreImpl
import dev.ostrom.cli.platform.Environment
import dev.ostrom.cli.platform.FileStoreImpl
import dev.ostrom.cli.platform.OperatingSystem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class UsageStatisticsTest {
    private class FakeEnvironment(private val values: Map<String, String>) : Environment {
        override fun get(name: String) = values[name]
        override val operatingSystem = OperatingSystem.MACOS
        override val architecture = "arm64"
    }

    private val store = ConfigStoreImpl(FileStoreImpl(FakeFileSystem()), "/home/maya/.config/ostrom/config.toml")
    private val sent = mutableListOf<Pair<String, String>>()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_800_000_000)
    }

    private fun usage(environment: Map<String, String> = emptyMap(), key: String = "phc_test") = UsageStatistics(
        environment = FakeEnvironment(environment),
        config = store,
        projectKey = key,
        version = "0.2.0",
        send = { url, body -> sent += url to body },
        clock = clock,
        newId = { "install-1" },
    )

    private val known = setOf("claude", "doctor", "telemetry")

    @Test
    fun offUntilTurnedOnAndNothingIsSentMeanwhile() = runTest {
        assertEquals(UsageState.OFF, usage().state())
        usage().commandRun("doctor", known, 0, 5.milliseconds)

        assertTrue(sent.isEmpty())
        assertNull(store.get(ConfigKey.TELEMETRY_ID))
    }

    @Test
    fun enableMakesOneInstallIdAndDisableDeletesIt() {
        usage().enable(store)
        usage().enable(store)
        assertEquals("on", store.get(ConfigKey.TELEMETRY))
        assertEquals("install-1", store.get(ConfigKey.TELEMETRY_ID))
        assertEquals(UsageState.ON, usage().state())

        usage().disable(store)
        assertEquals("off", store.get(ConfigKey.TELEMETRY))
        assertNull(store.get(ConfigKey.TELEMETRY_ID))
        assertEquals(UsageState.OFF, usage().state())
    }

    @Test
    fun theEnvironmentAndTheBuildWinOverTheSetting() {
        usage().enable(store)

        for (value in listOf("0", "false", "OFF", "no")) {
            assertEquals(UsageState.OFF_BY_ENVIRONMENT, usage(mapOf("OSTROM_TELEMETRY" to value)).state())
        }
        assertEquals(UsageState.ON, usage(mapOf("OSTROM_TELEMETRY" to "1")).state())
        assertEquals(UsageState.OFF_BY_ENVIRONMENT, usage(mapOf("DO_NOT_TRACK" to "1")).state())
        assertEquals(UsageState.ON, usage(mapOf("DO_NOT_TRACK" to "0")).state())
        assertEquals(UsageState.NOT_IN_THIS_BUILD, usage(key = " ").state())
        val noHome = UsageStatistics(FakeEnvironment(emptyMap()), null, "phc", "0.2.0", { _, _ -> })
        assertEquals(UsageState.OFF, noHome.state())
    }

    @Test
    fun onceOnACommandSendsItsNameClassBucketAndPlatformOnly() = runTest {
        usage().enable(store)
        usage().commandRun("doctor", known, 1, 42.milliseconds)

        val (url, body) = sent.single()
        assertEquals(UsageStatistics.BATCH_URL, url)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("phc_test", json.getValue("api_key").jsonPrimitive.content)
        val event = json.getValue("batch").jsonArray.single().jsonObject
        assertEquals("cli_command_run", event.text("event"))
        assertEquals("install-1", event.text("distinct_id"))
        assertEquals("2027-01-15T08:00:00Z", event.text("timestamp"))
        val properties = event.getValue("properties").jsonObject
        assertEquals("doctor", properties.text("command"))
        assertEquals("error", properties.text("exit_code_class"))
        assertEquals("30-100", properties.text("duration_ms_bucket"))
        assertEquals("macOS", properties.text("os"))
        assertEquals("arm64", properties.text("arch"))
        assertEquals("0.2.0", properties.text("version"))
        assertEquals("ostrom-cli", properties.text("\$lib"))
        assertEquals(false, properties.getValue("\$process_person_profile").jsonPrimitive.boolean)
    }

    @Test
    fun unknownWordsAreNeverSentAndPlumbingIsNeverMeasured() = runTest {
        usage().enable(store)
        usage().commandRun("https://secret.example/repo", known, 64, 1.milliseconds)
        usage().commandRun(null, known, 0, 1.milliseconds)
        for (plumbing in listOf("hook", "mcp", "wait", "check")) usage().commandRun(plumbing, known, 0, 1.milliseconds)

        val commands = sent.map { (_, body) ->
            val event = Json.parseToJsonElement(body).jsonObject.getValue("batch").jsonArray.single().jsonObject
            event.getValue("properties").jsonObject.text("command")
        }
        assertEquals(listOf("unknown", "home"), commands)
        assertTrue(sent.none { it.second.contains("secret") })
    }

    @Test
    fun anEnabledSettingWithoutAnIdSendsNothing() = runTest {
        store.set(ConfigKey.TELEMETRY, "on")
        usage().commandRun("doctor", known, 0, 1.milliseconds)

        assertTrue(sent.isEmpty())
    }

    @Test
    fun exitCodesAndDurationsAreReducedToClassesAndRanges() {
        assertEquals("ok", UsageStatistics.exitClass(0))
        assertEquals("usage", UsageStatistics.exitClass(64))
        assertEquals("error", UsageStatistics.exitClass(70))
        assertEquals("<10", UsageStatistics.durationBucket(9))
        assertEquals("10-30", UsageStatistics.durationBucket(10))
        assertEquals("3000-10000", UsageStatistics.durationBucket(9_999))
        assertEquals(">=10000", UsageStatistics.durationBucket(10_000))
    }

    @Test
    fun theDefaultIdIsARandomUuid() {
        val default = UsageStatistics(FakeEnvironment(emptyMap()), store, "phc", "0.2.0", { _, _ -> })
        default.enable(store)

        assertTrue(Regex("^[0-9a-f-]{36}$").matches(store.get(ConfigKey.TELEMETRY_ID).orEmpty()))
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
