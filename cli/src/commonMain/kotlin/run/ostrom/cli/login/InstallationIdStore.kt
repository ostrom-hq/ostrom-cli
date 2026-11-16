// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.login

import run.ostrom.cli.platform.FileStore
import run.ostrom.core.api.models.DeviceLimits
import kotlin.uuid.Uuid

/**
 * The non-secret logical installation id (`installation-id` in the CLI folder, beside `config.toml`), as TeamCode's
 * `InstallationIdStore` (docs/devices.md, Installation identity and registration): a random lowercase UUID, created
 * once, that `ostrom login` sends so the server registers this workstation as one device. It deliberately survives
 * `ostrom logout` and a change of server.
 *
 * Two first runs agree on one value: the id is created under the file's exclusive lock ([FileStore.exclusive]), and
 * read again there before one is drawn, so the second run takes the first one's. A file that holds anything but an id
 * is refused and never overwritten.
 *
 * @param files The CLI's files.
 * @param path The path of `installation-id`.
 * @param sleep Waits the given milliseconds between two reads of a file another first run is publishing (virtual in
 *   tests).
 */
class InstallationIdStore(
    private val files: FileStore,
    private val path: String,
    private val sleep: suspend (Long) -> Unit,
) {
    /**
     * This installation's id, created when there is none yet.
     *
     * @throws InstallationIdException When the file holds something else than an id, or cannot be read.
     * @throws run.ostrom.cli.platform.FileWriteException When the id cannot be written.
     */
    suspend fun id(): String {
        read()?.let { return it }
        // the read happens again under the lock, so a run that created it meanwhile keeps its id
        val created = files.exclusive(path) {
            if (files.exists(path)) null else Uuid.random().toString().also { files.write(path, it) }
        }
        return created ?: read() ?: throw InstallationIdException("$path could not be created")
    }

    private suspend fun read(): String? {
        if (!files.exists(path)) return null
        val id = readRetrying()
        if (!DeviceLimits.INSTALLATION_ID.matches(id)) {
            throw InstallationIdException("$path is not an Ostrom installation identity")
        }
        return id
    }

    /**
     * Windows can refuse to open a file for a moment while another first run publishes it (a sharing violation reads
     * as "file not found" although it exists). The file is complete once published, so a short, bounded retry reads
     * it (TeamCode #281: 20 attempts 25 ms apart); a real failure still surfaces after the last attempt.
     */
    private suspend fun readRetrying(): String {
        repeat(READ_ATTEMPTS - 1) {
            files.read(path)?.let { return it }
            sleep(RETRY_DELAY_MILLIS)
        }
        return files.read(path) ?: throw InstallationIdException("$path cannot be read")
    }

    private companion object {
        const val READ_ATTEMPTS = 20
        const val RETRY_DELAY_MILLIS = 25L
    }
}

/** The installation identity is unreadable, or holds something else than an id (exit 73). */
class InstallationIdException(message: String) : Exception(message)
