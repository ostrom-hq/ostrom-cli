// SPDX-License-Identifier: Apache-2.0
// A failure of the network, a socket or a process is logged by its class and the runner goes on; cancellation is
// rethrown first (scripts/check-conventions.py).
@file:Suppress("TooGenericExceptionCaught")

package run.ostrom.cli.runner

import io.ktor.network.selector.SelectorManager
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The runner's environment socket, as TeamCode's `EnvironmentBroker`: the agent's `CLAUDE_ENV_FILE` script asks it
 * for the owner's current variables before each Bash command (`ostrom cloud environment`). One `environment` line
 * per connection, answered by one [EnvironmentAnswer] line; anything else is answered with nothing. The values stay in
 * the runner's memory, and the agent's container receives them only when one of its commands asks.
 */
object EnvironmentSocket {
    /** The request line. */
    const val REQUEST = "environment"

    /** The largest request line, in bytes. */
    const val MAX_LINE = 1024

    /** Requests answered at once. */
    const val MAX_CONCURRENT = 8

    /** The longest a request may take to arrive. */
    val TIMEOUT: Duration = 10.seconds

    /** The longest the agent's command waits for the runner. */
    val CLIENT_TIMEOUT: Duration = 15.seconds

    private val JSON = Json { ignoreUnknownKeys = true }
    private val permits = Semaphore(MAX_CONCURRENT)

    /**
     * Where the socket is: next to the credential socket, in the directory the agent's container mounts.
     *
     * @param credentialSocket The credential socket.
     *
     * @return The environment socket.
     */
    fun socketFor(credentialSocket: String): String = "${directory(credentialSocket)}/environment.sock"

    /**
     * Where the `CLAUDE_ENV_FILE` script is: next to the sockets; it holds no value.
     *
     * @param credentialSocket The credential socket.
     *
     * @return The script's path.
     */
    fun scriptFor(credentialSocket: String): String = "${directory(credentialSocket)}/agent-env.sh"

    /**
     * Writes the `CLAUDE_ENV_FILE` script ([AgentEnvironment.cloudScript]), replacing the file atomically.
     *
     * @param path Where.
     * @param binary The `ostrom` binary.
     * @param socket The environment socket.
     * @param files The file system.
     */
    fun writeScript(path: String, binary: String, socket: String, files: FileSystem = SystemFileSystem) {
        val temporary = Path("$path.tmp")
        files.sink(temporary).buffered().use { it.writeString(AgentEnvironment.cloudScript(binary, socket)) }
        files.atomicMove(temporary, Path(path))
    }

    private fun directory(path: String) = path.substringBeforeLast('/', ".")

    /**
     * Listens and answers each request with [variables], until cancelled.
     *
     * @param selector The socket selector.
     * @param path The socket's path.
     * @param variables The owner's variables.
     * @param log Writes a line to the container's log (never a value).
     * @param ready Called once the socket listens, with its path (the runner opens it to the agent's uid).
     */
    suspend fun serve(
        selector: SelectorManager,
        path: String,
        variables: RunnerVariables,
        log: (String) -> Unit,
        ready: (String) -> Unit,
    ) = AgentSocket.listen(selector, path, "environment", log, ready) { input, output ->
        answer(input, output, variables, log)
    }

    /**
     * Answers one connection.
     *
     * @param input From the agent's command.
     * @param output To it.
     * @param variables The owner's variables.
     * @param log Writes a line to the container's log.
     * @param timeout The longest the request may take to arrive.
     */
    suspend fun answer(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        variables: RunnerVariables,
        log: (String) -> Unit,
        timeout: Duration = TIMEOUT,
    ) {
        val request = try {
            withTimeoutOrNull(timeout) { readSocketLine(input, MAX_LINE) }
        } catch (_: IllegalArgumentException) {
            null
        }
        if (request != REQUEST) return
        // The variables never fail: a server that cannot answer leaves the last values.
        val answer = permits.withPermit { variables.forCommand() }
        try {
            val line = JSON.encodeToString(EnvironmentAnswer.serializer(), answer) + "\n"
            output.writeByteArray(line.encodeToByteArray())
            output.flush()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log("environment reply failed: ${error::class.simpleName}")
        }
    }

    /**
     * Asks the runner for the current variables (`ostrom cloud environment`, in the agent's container): nothing is
     * logged, and a runner that cannot be reached or answers nothing readable gives null.
     *
     * @param connect Opens a connection to the runner's socket.
     * @param timeout The longest to wait.
     *
     * @return The answer, or null.
     */
    suspend fun ask(connect: suspend () -> AgentConnection, timeout: Duration = CLIENT_TIMEOUT): EnvironmentAnswer? {
        val connection = try {
            connect()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        return try {
            withTimeoutOrNull(timeout) {
                connection.output.writeByteArray("$REQUEST\n".encodeToByteArray())
                connection.output.flush()
                readSocketLine(connection.input, MAX_ANSWER)?.let {
                    JSON.decodeFromString(EnvironmentAnswer.serializer(), it)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } finally {
            withContext(NonCancellable) { connection.close() }
        }
    }

    /** The largest answer, in bytes: the server bounds the owner's variables well below. */
    private const val MAX_ANSWER = 4 * 1024 * 1024
}
