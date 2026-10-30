// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.platform

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.SHA256
import okio.Path.Companion.toPath
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64

/**
 * Where a [SecretStore] keeps its secrets.
 *
 * @property displayName How doctor and login name it.
 * @property warning What to tell the developer when this store is used, or null when there is nothing to say.
 */
enum class SecretBackend(val displayName: String, val warning: String? = null) {
    KEYCHAIN("macOS Keychain"),
    SECRET_SERVICE("Secret Service (libsecret)"),
    CREDENTIAL_MANAGER("Windows Credential Manager"),
    ENCRYPTED_FILE(
        "an encrypted file",
        "No Secret Service on this device: secrets are kept in an encrypted file only you can read. " +
            "Install and unlock GNOME Keyring or KWallet to keep them in the system store.",
    ),
}

/** The secret store could not keep or give back a secret; [message] says what to do. */
class SecretStoreException(override val message: String) : Exception(message)

/**
 * The secrets of this device, the device token of each server first (security audit S2-32). They live in the
 * system's own store: Keychain on macOS, the Secret Service (libsecret) on Linux, Credential Manager on Windows. Only
 * a Linux without a Secret Service falls back to an encrypted file (0600), and says so ([SecretBackend.warning]).
 * Never a file that reads in clear, never an environment variable an agent would inherit.
 */
interface SecretStore {
    /** Where the secrets are kept. */
    val backend: SecretBackend

    /**
     * The secret called [name], or null when there is none.
     *
     * @throws SecretStoreException When the store refuses to answer (locked, unreachable).
     */
    fun read(name: String): String?

    /**
     * Keeps [secret] as [name], replacing any secret of that name.
     *
     * @throws SecretStoreException When the store refuses it.
     */
    fun write(name: String, secret: String)

    /**
     * Forgets the secret called [name]; nothing happens when there is none.
     *
     * @throws SecretStoreException When the store refuses it.
     */
    fun delete(name: String)
}

/**
 * The secret store of this device: the system's own, or the encrypted file where the system has none.
 *
 * @param environment Gives the CLI folder, where the encrypted file goes.
 * @param files Reads the machine identifier and keeps the encrypted file.
 *
 * @throws SecretStoreException When there is neither a system store nor a CLI folder and a machine identifier.
 */
fun systemSecretStore(environment: Environment, files: FileStore): SecretStore =
    secretStoreOf(systemVault(), environment, files)

/** [systemSecretStore] with the system store given, for tests. */
internal fun secretStoreOf(vault: Vault?, environment: Environment, files: FileStore): SecretStore {
    if (vault != null && vault.isAvailable()) return VaultSecretStore(vault)
    val directory = environment.configDirectory ?: throw SecretStoreException(NO_SECRET_STORE)
    val material = machineKeyMaterial(files, environment) ?: throw SecretStoreException(NO_SECRET_STORE)
    return EncryptedFileSecretStore(files, (directory.toPath() / SECRETS_FILE).toString(), material)
}

/** The service name every secret of the CLI is filed under in the system store. */
internal const val SECRET_SERVICE_NAME = "dev.ostrom.cli"

/** The largest secret the system stores give back (Credential Manager caps a blob at 2,560 bytes). */
internal const val MAX_SECRET_BYTES = 2_560

private const val NO_SECRET_STORE =
    "No secret store on this device: install and unlock a Secret Service (GNOME Keyring, KWallet), or set HOME."

private const val SECRETS_FILE = "secrets.enc"

/** What a system store answered: one of the `VAULT_` statuses, the system's error code, the secret read. */
internal class VaultAnswer(val status: Int, val code: Int = 0, val secret: ByteArray = ByteArray(0))

/** Statuses of [VaultAnswer], the same in the three C shims. */
internal const val VAULT_OK = 0
internal const val VAULT_NOT_FOUND = 1
internal const val VAULT_TOO_LARGE = 2

/** The system store, as its C shim exposes it: one secret per service and account. */
internal interface Vault {
    /** Which store this is. */
    val backend: SecretBackend

    /** True when the store answers (on Linux, a Secret Service runs on the session bus). */
    fun isAvailable(): Boolean

    /** The secret of [service] and [account]. */
    fun read(service: String, account: String): VaultAnswer

    /** Keeps [secret] for [service] and [account], replacing the old one. */
    fun write(service: String, account: String, secret: ByteArray): VaultAnswer

    /** Forgets the secret of [service] and [account]. */
    fun delete(service: String, account: String): VaultAnswer
}

/** The system store of this platform, or null where there is none (the JVM test target). */
internal expect fun systemVault(): Vault?

/** [SecretStore] over the system store [vault]: one entry per secret, under [SECRET_SERVICE_NAME]. */
internal class VaultSecretStore(private val vault: Vault) : SecretStore {
    override val backend: SecretBackend
        get() = vault.backend

    override fun read(name: String): String? {
        val answer = vault.read(SECRET_SERVICE_NAME, name)
        return when (answer.status) {
            VAULT_OK -> answer.secret.decodeToString()
            VAULT_NOT_FOUND -> null
            else -> throw failure("read", name, answer)
        }
    }

    override fun write(name: String, secret: String) {
        val bytes = secret.encodeToByteArray()
        if (bytes.size > MAX_SECRET_BYTES) throw SecretStoreException("The secret $name is too large to keep.")
        val answer = vault.write(SECRET_SERVICE_NAME, name, bytes)
        if (answer.status != VAULT_OK) throw failure("keep", name, answer)
    }

    override fun delete(name: String) {
        val answer = vault.delete(SECRET_SERVICE_NAME, name)
        if (answer.status != VAULT_OK && answer.status != VAULT_NOT_FOUND) throw failure("forget", name, answer)
    }

    private fun failure(action: String, name: String, answer: VaultAnswer) = SecretStoreException(
        if (answer.status == VAULT_TOO_LARGE) {
            "The secret $name in ${vault.backend.displayName} is too large to read."
        } else {
            "${vault.backend.displayName} could not $action the secret $name (error ${answer.code}): " +
                "unlock it and try again."
        },
    )
}

/**
 * [SecretStore] in an encrypted file, for a Linux without a Secret Service. Each secret is sealed with AES-256-GCM
 * (its name as associated data, so entries cannot be swapped). The key is derived from the machine identifier and the
 * user: a copy of the file (a backup, a synced dotfiles folder) does not open on another machine. The file is 0600;
 * the encryption is no defence against a program running as the same user on the same machine, which is why the
 * system store is preferred whenever there is one.
 *
 * Format: a first line `ostrom-secrets 1`, then one `<name> <base64 of IV, ciphertext and tag>` line per secret.
 *
 * @param keyMaterial The machine identifier and the user, from [machineKeyMaterial].
 */
internal class EncryptedFileSecretStore(
    private val files: FileStore,
    private val path: String,
    keyMaterial: ByteArray,
) : SecretStore {
    private val cipher = CryptographyProvider.Default.get(AES.GCM).keyDecoder()
        .decodeFromByteArrayBlocking(AES.Key.Format.RAW, derivedKey(keyMaterial))
        .cipher()

    override val backend: SecretBackend = SecretBackend.ENCRYPTED_FILE

    override fun read(name: String): String? {
        val sealed = entries()[name] ?: return null
        return open(name, sealed)
    }

    override fun write(name: String, secret: String) {
        requireName(name)
        val sealed = cipher.encryptBlocking(secret.encodeToByteArray(), name.encodeToByteArray())
        save(entries() + (name to Base64.encode(sealed)))
    }

    override fun delete(name: String) {
        val entries = entries()
        if (name in entries) save(entries - name)
    }

    /** The secret sealed in [sealed], or null when it does not open here (another machine, a damaged file). */
    private fun open(name: String, sealed: String): String? = nullOnFailure {
        cipher.decryptBlocking(Base64.decode(sealed), name.encodeToByteArray()).decodeToString()
    }

    private fun entries(): Map<String, String> {
        val lines = files.read(path).orEmpty().lines()
        if (lines.first() != HEADER) return emptyMap()
        return lines.drop(1)
            .filter { ' ' in it }
            .associate { it.substringBefore(' ') to it.substringAfter(' ') }
    }

    private fun save(entries: Map<String, String>) {
        val lines = entries.entries.joinToString("") { (name, sealed) -> "$name $sealed\n" }
        files.write(path, "$HEADER\n$lines")
    }

    private fun requireName(name: String) {
        if (name.isEmpty() || name.any { it.isWhitespace() }) {
            throw SecretStoreException("A secret name cannot be empty or hold spaces: \"$name\".")
        }
    }
}

/**
 * [block]'s value, or null when it throws: bad base64 and a failed tag check throw different types per provider
 * (`IllegalArgumentException`, OpenSSL's `CryptographyException`, the JDK's `AEADBadTagException`).
 */
@Suppress("TooGenericExceptionCaught")
internal fun <T> nullOnFailure(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** First line of the encrypted secrets file, with its format version. */
private const val HEADER = "ostrom-secrets 1"

/** The 256-bit AES key of the encrypted file: SHA-256 of a fixed label and [material]. */
private fun derivedKey(material: ByteArray): ByteArray =
    CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(KEY_LABEL.encodeToByteArray() + material)

private const val KEY_LABEL = "ostrom secrets file v1\n"

/**
 * What the encrypted file's key is derived from: the machine identifier (systemd's, else D-Bus's) and the user. Null
 * when the machine has no identifier.
 */
internal fun machineKeyMaterial(files: FileStore, environment: Environment): ByteArray? {
    for (file in MACHINE_ID_FILES) {
        val identifier = files.read(file).orEmpty().trim()
        if (identifier.isNotEmpty()) return "$identifier\n${environment["USER"].orEmpty()}".encodeToByteArray()
    }
    return null
}

private val MACHINE_ID_FILES = listOf("/etc/machine-id", "/var/lib/dbus/machine-id")
