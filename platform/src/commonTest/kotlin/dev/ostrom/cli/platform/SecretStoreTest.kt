// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretStoreTest {
    private class FakeEnvironment(private val values: Map<String, String>) : Environment {
        override fun get(name: String): String? = values[name]
        override val operatingSystem = OperatingSystem.LINUX
        override val architecture = "x64"
    }

    /** A system store keeping its secrets in memory, answering [failWith] instead when it is set. */
    private class FakeVault(private val available: Boolean = true) : Vault {
        val secrets = mutableMapOf<String, ByteArray>()
        var failWith: VaultAnswer? = null
        override val backend = SecretBackend.KEYCHAIN
        override fun isAvailable() = available
        override fun read(service: String, account: String): VaultAnswer = failWith
            ?: secrets["$service/$account"]?.let { VaultAnswer(VAULT_OK, secret = it) }
            ?: VaultAnswer(VAULT_NOT_FOUND)

        override fun write(service: String, account: String, secret: ByteArray): VaultAnswer {
            failWith?.let { return it }
            secrets["$service/$account"] = secret
            return VaultAnswer(VAULT_OK)
        }

        override fun delete(service: String, account: String): VaultAnswer {
            failWith?.let { return it }
            val removed = secrets.remove("$service/$account") != null
            return VaultAnswer(if (removed) VAULT_OK else VAULT_NOT_FOUND)
        }
    }

    private val fileSystem = FakeFileSystem()
    private val files = FileStoreImpl(fileSystem)
    private val home = FakeEnvironment(mapOf("HOME" to "/home/maya", "USER" to "maya"))
    private val secretsFile = "/home/maya/.config/ostrom/secrets.enc"

    private fun machine(identifier: String = "4c4c4544004d3510804bb4c04f4d3832") {
        fileSystem.createDirectories("/etc".toPath())
        fileSystem.write("/etc/machine-id".toPath()) { writeUtf8("$identifier\n") }
    }

    @Test
    fun theSystemStoreKeepsReplacesAndForgetsSecrets() {
        val vault = FakeVault()
        val store = secretStoreOf(vault, home, files)
        assertEquals(SecretBackend.KEYCHAIN, store.backend)
        assertNull(store.read("https://ostrom.run"))
        store.write("https://ostrom.run", "osd_first")
        store.write("https://ostrom.run", "osd_second")
        assertEquals("osd_second", store.read("https://ostrom.run"))
        assertEquals(setOf("dev.ostrom.cli/https://ostrom.run"), vault.secrets.keys)
        store.delete("https://ostrom.run")
        store.delete("https://ostrom.run")
        assertNull(store.read("https://ostrom.run"))
    }

    @Test
    fun aSystemStoreThatRefusesSaysWhatToDo() {
        val vault = FakeVault().apply { failWith = VaultAnswer(3, code = -25308) }
        val store = secretStoreOf(vault, home, files)
        val read = assertFailsWith<SecretStoreException> { store.read("s") }
        val expected = "macOS Keychain could not read the secret s (error -25308): macOS needs your permission and " +
            "cannot ask here: run ostrom login in a Terminal of your desktop session and choose Always Allow."
        assertEquals(expected, read.message)
        vault.failWith = VaultAnswer(3, code = -128)
        val denied = assertFailsWith<SecretStoreException> { store.read("s") }.message
        assertEquals("macOS Keychain could not read the secret s (error -128): unlock it and try again.", denied)
        vault.failWith = VaultAnswer(3, code = -25308)
        assertTrue(assertFailsWith<SecretStoreException> { store.write("s", "x") }.message!!.contains("could not keep"))
        assertTrue(assertFailsWith<SecretStoreException> { store.delete("s") }.message!!.contains("could not forget"))
        vault.failWith = VaultAnswer(VAULT_TOO_LARGE)
        assertEquals(
            "The secret s in macOS Keychain is too large to read.",
            assertFailsWith<SecretStoreException> { store.read("s") }.message,
        )
        vault.failWith = VaultAnswer(VAULT_UNREACHABLE)
        val stopped = assertFailsWith<SecretStoreException> { store.read("s") }.message!!
        assertTrue(stopped.startsWith("macOS Keychain stopped answering"), stopped)
        vault.failWith = null
        assertFailsWith<SecretStoreException> { store.write("s", "x".repeat(MAX_SECRET_BYTES + 1)) }
        store.write("s", "x".repeat(MAX_SECRET_BYTES))
        assertEquals(MAX_SECRET_BYTES, store.read("s")!!.length)
    }

    @Test
    fun withoutASystemStoreSecretsGoToAnEncryptedFileThatSaysSo() {
        machine()
        val store = secretStoreOf(FakeVault(available = false), home, files)
        assertEquals(SecretBackend.ENCRYPTED_FILE, store.backend)
        assertNotNull(store.backend.warning)
        assertNull(store.read("https://ostrom.run"))
        store.write("https://ostrom.run", "osd_secret_token")
        store.write("https://eu.ostrom.run", "osd_other")
        store.write("https://ostrom.run", "osd_replaced")
        assertEquals("osd_replaced", store.read("https://ostrom.run"))
        assertEquals("osd_other", store.read("https://eu.ostrom.run"))
        val text = files.read(secretsFile)!!
        assertTrue(text.startsWith("ostrom-secrets 1\n"), text)
        assertTrue("osd_" !in text, "the file must not hold a secret in clear: $text")
        store.delete("https://eu.ostrom.run")
        store.delete("https://never.written")
        assertNull(store.read("https://eu.ostrom.run"))
        assertEquals("osd_replaced", store.read("https://ostrom.run"))
    }

    @Test
    fun theEncryptedFileDoesNotOpenOnAnotherMachineNorWithSwappedEntries() {
        machine()
        secretStoreOf(null, home, files).write("a", "secret-a")
        secretStoreOf(null, home, files).write("b", "secret-b")
        val text = files.read(secretsFile)!!
        val a = text.lines()[1].substringAfter(' ')
        val b = text.lines()[2].substringAfter(' ')
        files.write(secretsFile, "ostrom-secrets 1\na $b\nb $a\nbroken !!!not-base64\nno-separator\n")
        val swapped = secretStoreOf(null, home, files)
        assertNull(swapped.read("a"))
        assertNull(swapped.read("broken"))
        assertNull(swapped.read("no-separator"))
        files.write(secretsFile, text)
        assertNull(secretStoreOf(null, home, files, user = "1001").read("a"), "another user")
        assertEquals("secret-a", secretStoreOf(null, FakeEnvironment(mapOf("HOME" to "/home/maya")), files).read("a"))
        machine("another-machine")
        assertNull(secretStoreOf(null, home, files).read("a"))
    }

    @Test
    fun anUnknownFileFormatReadsAsNoSecretIsNeverReplacedAndNamesAreChecked() {
        machine()
        files.write(secretsFile, "ostrom-secrets 2\nname value\n")
        val store = secretStoreOf(null, home, files)
        assertNull(store.read("name"))
        assertFailsWith<SecretStoreException> { store.write("other", "x") }
        assertFailsWith<SecretStoreException> { store.delete("name") }
        assertEquals("ostrom-secrets 2\nname value\n", files.read(secretsFile))
        // An empty file (touched, or cut short) holds no secret and is written over.
        files.write(secretsFile, "")
        store.write("name", "x")
        assertEquals("x", store.read("name"))
        assertFailsWith<SecretStoreException> { store.write("", "x") }
        assertFailsWith<SecretStoreException> { store.write("two words", "x") }
    }

    @Test
    fun theMachineIdentifierComesFromSystemdThenDbus() {
        assertNull(machineKeyMaterial(files, "1000"))
        fileSystem.createDirectories("/var/lib/dbus".toPath())
        fileSystem.write("/var/lib/dbus/machine-id".toPath()) { writeUtf8("dbus-id") }
        assertEquals("dbus-id\n1000", machineKeyMaterial(files, "1000")!!.decodeToString())
        machine("  ")
        assertEquals("dbus-id\n1000", machineKeyMaterial(files, "1000")!!.decodeToString())
        machine("systemd-id")
        assertEquals("systemd-id\n0", machineKeyMaterial(files, "0")!!.decodeToString())
    }

    @Test
    fun withNeitherAStoreNorAFolderNorAMachineThereIsNoPlaceForSecrets() {
        val noHome = assertFailsWith<SecretStoreException> { secretStoreOf(null, FakeEnvironment(emptyMap()), files) }
        assertTrue(noHome.message!!.startsWith("No secret store on this device"))
        assertFailsWith<SecretStoreException> { secretStoreOf(null, home, files) }
    }

    @Test
    fun theStoreOfThisPlatformIsTheSystemsOrTheFile() {
        machine()
        // The JVM test target has no system store: the file. Native targets prove theirs in the vault tests.
        assertIs<SecretStore>(systemSecretStore(home, files))
    }

    @Test
    fun aFileThatCannotBeWrittenIsASecretStoreRefusal() {
        machine()
        // The CLI folder is a file: its secrets file cannot be created.
        fileSystem.createDirectories("/home/maya/.config".toPath())
        fileSystem.write("/home/maya/.config/ostrom".toPath()) { writeUtf8("not a folder") }
        val refused = assertFailsWith<SecretStoreException> { secretStoreOf(null, home, files).write("a", "x") }
        assertTrue(refused.message!!.startsWith("Could not write"), refused.message)
    }

    @Test
    fun aFileThatCannotBeReadIsNeverReplaced() {
        machine()
        fileSystem.createDirectories(secretsFile.toPath())
        val store = secretStoreOf(null, home, files)
        assertNull(store.read("a"))
        val refused = assertFailsWith<SecretStoreException> { store.write("a", "x") }
        assertTrue(refused.message!!.endsWith("cannot be read: check that it is yours, then try again."))
    }

    @Test
    fun aFailureReadsAsNothingButACancellationStaysOne() {
        assertEquals(1, nullOnFailure { 1 })
        assertNull(nullOnFailure { error("tag check failed") })
        assertFailsWith<CancellationException> { nullOnFailure { throw CancellationException("stop") } }
    }
}
