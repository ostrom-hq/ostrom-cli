// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.cli.runner

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.getuid
import platform.posix.lstat
import platform.posix.stat
import kotlinx.cinterop.staticCFunction
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.SIGTERM
import platform.posix._exit
import platform.posix.chmod
import platform.posix.getenv
import platform.posix.signal
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * The hidden commands of a cloud session's containers, run from the `ostrom` binary its init container copied in:
 * `ostrom cloud runner` is the runner (its container holds the runner token), `ostrom cloud agent-supervisor` the
 * agent's side (its own container and uid, without the token, S2-49), and `ostrom cloud check-host` the side that runs
 * the hooks' checks for the runner (its own container, without the token, the workspace mounted, its socket in a
 * directory the agent's container does not mount). `ostrom cloud credential` is git's credential helper in the agent's
 * container, and `ostrom cloud agent-state` Claude Code's hook that reports its background work. `ostrom cloud
 * machine` is a registered runner on a machine of the organization: it signs of life with its
 * key until the server refuses it.
 *
 * @param command One of [CloudProcess]'s commands.
 * @param args The arguments after it.
 */
actual fun runCloudProcess(command: String, args: List<String>): Nothing {
    val environment = readEnvironment()
    val socket = environment["OSTROM_AGENT_SOCKET"]?.takeIf { it.isNotBlank() } ?: AgentSocket.DEFAULT_PATH
    when (command) {
        CloudProcess.MACHINE -> machine(environment)
        CloudProcess.CREDENTIAL -> credential(args.firstOrNull(), environment)
        CloudProcess.AGENT_STATE -> agentState(environment)
        // Terminals on a PTY; the preparation's commands on pipes, their log written as the agent's uid.
        CloudProcess.AGENT_SUPERVISOR -> host(socket, AgentHost(PtyTerminal, commands = PtyTerminal.Pipes))
        CloudProcess.CHECK_HOST -> checkHost(
            environment["OSTROM_CHECK_HOST_SOCKET"]?.takeIf { it.isNotBlank() }
                ?: RunnerConfig.DEFAULT_CHECK_HOST_SOCKET,
            environment["OSTROM_WORKSPACE"] ?: RunnerConfig.DEFAULT_WORKSPACE,
        )
        else -> runner(socket, environment)
    }
}

/** The runner (`ostrom cloud runner`): relays the agent's terminal and heartbeats until the server ends it. */
private fun runner(socket: String, environment: Map<String, String>): Nothing {
    val config = try {
        RunnerConfig.from(environment) { path ->
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        }
    } catch (error: IllegalArgumentException) {
        log("invalid configuration: ${error.message}")
        exitProcess(EXIT_BAD_CONFIGURATION)
    }.let { config -> selfPath()?.let { config.copy(binary = it) } ?: config }
    log("starting $config")
    val code = runBlocking {
        val client = HttpClient {
            expectSuccess = false
            install(WebSockets)
            install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS }
        }
        try {
            val selector = SelectorManager(Dispatchers.IO)
            val terminals = RemoteTerminalStarter({ AgentSocket.connect(selector, socket) })
            val server = HttpServerApi(client, config, this)
            // The runner listens for the check host (R4): the side of the checks it trusts is its own uid.
            lateinit var link: CheckLink
            val listener = LinkListener(this, config.checkHostSocket) { ready ->
                AgentSocket.accept(selector, config.checkHostSocket, "check link", ::log, ready) { link.accept(it) }
            }
            link = CheckLink(before = listener::heal)
            listener.start()
            val checks = RunnerChecks(link, server, config.workspace, environment, Clock.System)
            // The agent's uid reaches the checks' socket: the directory is the agent socket's, mounted by both.
            val checksJob = launch {
                ChecksSocket.serve(selector, config.checksSocket, checks::run, ::log) { chmod(it, OPEN_MODE.convert()) }
            }
            val gitHub = GitHubCredentialSource(server::gitHubToken, Clock.System)
            val gitHubJob = gitHubAccess(selector, config, gitHub)
            val preparer = preparer(config, server, terminals, gitHub)
            val runner = Runner(config, server, terminals, environment, Clock.System, ::log, prepare = preparer)
            // The agent's hooks report its background work there, as its uid: the agent socket's directory.
            val stateJob = launch {
                AgentState.serve(selector, config.stateSocket, runner::agentState, ::log) {
                    chmod(it, OPEN_MODE.convert())
                }
            }
            try {
                runner.run()
            } finally {
                stateJob.cancel()
                gitHubJob.cancelAndJoin()
                checksJob.cancel()
                listener.stop()
                selector.close()
            }
        } finally {
            client.close()
        }
    }
    log("exiting with $code")
    exitProcess(code)
}

/**
 * The session's preparation: the recipe's commands run on the agent host, which writes their log; the masked lines
 * reach the terminal, and the owner's GitHub token is masked too.
 */
private fun preparer(
    config: RunnerConfig,
    server: ServerApi,
    terminals: RemoteTerminalStarter,
    gitHub: GitHubCredentialSource,
) = Preparer { agentEnvironment, output ->
    val commands = AgentHostCommands(terminals::command, PreparationLog.path(config.workspace, config.epoch), output)
    EnvironmentPreparation(
        server,
        commands,
        agentEnvironment,
        config.workspace,
        "${config.binary} cloud credential",
        token = { gitHub.token()?.token },
        log = ::log,
    ).run()
}

/**
 * git and `gh` reach GitHub with the owner's token, which only the runner holds and asks the server for: the
 * credential socket answers git's helper, and the `gh` configuration is written before the agent starts, then kept
 * current. Cancelling the job stops both and removes the configuration.
 */
private suspend fun CoroutineScope.gitHubAccess(
    selector: SelectorManager,
    config: RunnerConfig,
    gitHub: GitHubCredentialSource,
): Job {
    val gh = GitHubCliRenewal(gitHub, GitHubCliConfig(config.gitHubConfigDirectory), Clock.System, ::log)
    // The agent starts once the configuration is written, or after a bounded wait: a slow server delays no session.
    val first = withTimeoutOrNull(GITHUB_FIRST_TIMEOUT) { gh.refresh() } ?: GitHubCredentialSource.RETRY
    return launch {
        launch {
            CredentialSocket.serve(selector, config.credentialSocket, gitHub::credential, ::log) {
                chmod(it, OPEN_MODE.convert())
            }
        }
        gh.run(first)
    }
}

/**
 * The runner's listening socket for the check host, kept its own (R4). The directory is the check host's too: a script
 * may remove the socket and listen at its path. Before each check the runner makes sure the path is still the socket
 * it bound (its uid, its inode), and binds again otherwise; the check host's process behind the impostor is killed
 * after the check that started it.
 *
 * @param scope Where the listener runs.
 * @param path The socket's path.
 * @param listen Listens on [path], calling its argument once bound.
 */
private class LinkListener(
    private val scope: CoroutineScope,
    private val path: String,
    private val listen: suspend (ready: (String) -> Unit) -> Unit,
) {
    private var job: Job? = null
    private var bound: ULong? = null

    /** Starts listening; returns once the socket is bound. */
    suspend fun start() {
        val ready = CompletableDeferred<Unit>()
        job = scope.launch {
            listen { socket ->
                chmod(socket, OPEN_MODE.convert())
                bound = ownInode(socket)
                ready.complete(Unit)
            }
        }
        ready.await()
    }

    /** Binds again when the path is no longer the socket this runner bound. */
    suspend fun heal() {
        val current = ownInode(path)
        if (current != null && current == bound) return
        log("the check link socket was replaced: listening again")
        job?.cancelAndJoin()
        start()
    }

    /** Stops listening. */
    fun stop() {
        job?.cancel()
    }

    /** The inode at [socket] when it is a file of this process's uid, else null. */
    private fun ownInode(socket: String): ULong? = memScoped {
        val status = alloc<stat>()
        if (lstat(socket, status.ptr) != 0 || status.st_uid != getuid()) null else status.st_ino.convert()
    }
}

/**
 * Git's credential helper (`ostrom cloud credential get`), in the agent's container: asks the runner's credential
 * socket for the remote git describes on stdin and prints what it answers. Every other operation, and every failure,
 * prints nothing and succeeds, so git moves on.
 */
private fun credential(operation: String?, environment: Map<String, String>): Nothing {
    if (operation == "get") {
        val socket = environment[RunnerConfig.CREDENTIAL_VARIABLE]?.takeIf { it.isNotBlank() }
            ?: RunnerConfig.DEFAULT_CREDENTIAL_SOCKET
        val request = CredentialHelper.parse(generateSequence { readlnOrNull() })
        val answer = runBlocking {
            val selector = SelectorManager(Dispatchers.IO)
            try {
                CredentialHelper.ask({ AgentSocket.connect(selector, socket) }, request)
            } finally {
                selector.close()
            }
        }
        print(CredentialHelper.format(answer))
    }
    exitProcess(0)
}

/**
 * Claude Code's `Stop` and `SessionEnd` hook (`ostrom cloud agent-state`), in the agent's container: reads the hook's
 * input, tells the runner whether background work remains ([AgentState]), prints nothing and succeeds, so the agent
 * never waits on it nor shows anything.
 */
private fun agentState(environment: Map<String, String>): Nothing {
    val socket = environment[RunnerConfig.STATE_VARIABLE]?.takeIf { it.isNotBlank() }
        ?: RunnerConfig.DEFAULT_STATE_SOCKET
    val payload = StringBuilder()
    while (payload.length < AgentState.MAX_PAYLOAD) {
        val line = readlnOrNull() ?: break
        payload.append(line).append('\n')
    }
    runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        try {
            AgentState.tell({ AgentSocket.connect(selector, socket) }, AgentState.background(payload.toString()))
        } finally {
            selector.close()
        }
    }
    exitProcess(0)
}

/** A host (agent or checks): serves the runner's terminals on [socket], until the pod stops it (SIGTERM). */
private fun host(socket: String, host: AgentHost): Nothing {
    // As pid 1 of its container, the host gets no default action for SIGTERM: without this, a stop waits the grace.
    signal(SIGTERM, staticCFunction<Int, Unit> { _exit(0) })
    runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        // The runner, another uid, connects through the group every container of the session is in (1000, the fsGroup).
        AgentSocket.serve(selector, socket, host, ::log) { chmod(it, SOCKET_MODE.convert()) }
    }
    exitProcess(0)
}

/** The binary this process runs (`/proc/self/exe`), at the same path in the agent's container; null elsewhere. */
private fun selfPath(): String? = realPath("/proc/self/exe")

internal fun log(line: String) {
    fprintf(stderr, "%s\n", "ostrom cloud: $line")
    fflush(stderr)
}

/** The process environment: `/proc/self/environ` on Linux, a few known variables elsewhere (development). */
private fun readEnvironment(): Map<String, String> {
    val proc = Path("/proc/self/environ")
    if (SystemFileSystem.exists(proc)) {
        return parseEnvironment(SystemFileSystem.source(proc).buffered().use { it.readString() })
    }
    return KNOWN_VARIABLES.mapNotNull { name -> getenv(name)?.toKString()?.let { name to it } }.toMap()
}

private val KNOWN_VARIABLES = listOf(
    "PATH", "HOME", "LANG", "USER", "SHELL", "OSTROM_SERVER_URL", "OSTROM_SESSION_ID", "OSTROM_EPOCH", "OSTROM_AGENT",
    "OSTROM_AGENT_COMMAND", "OSTROM_AGENT_MODEL", "OSTROM_AGENT_PROMPT", "OSTROM_AGENT_PROMPT_FILE",
    "OSTROM_REPOSITORY_URL", "OSTROM_REPOSITORY_REF", "OSTROM_RUNNER_TOKEN", "OSTROM_RUNNER_TOKEN_FILE",
    "OSTROM_WORKSPACE", "OSTROM_AGENT_SOCKET", "OSTROM_RUNNER_CHECKS", "OSTROM_CHECK_HOST_SOCKET",
    "OSTROM_RUNNER_ID", "OSTROM_RUNNER_KEY", "OSTROM_RUNNER_KEY_FILE", "OSTROM_RUNNER_INTERVAL_SECONDS",
    "OSTROM_DOCKER_API_URL", "OSTROM_DOCKER_NETWORK", "OSTROM_RUNNER_SESSION_IMAGE", "OSTROM_CREDENTIAL_SOCKET",
    "OSTROM_GH_CONFIG_DIR", "OSTROM_AGENT_STATE_SOCKET",
)

internal const val CONNECT_TIMEOUT_MILLIS = 10_000L

/** The longest the first GitHub token may take before the agent starts without it. */
private val GITHUB_FIRST_TIMEOUT = 10.seconds

/** rw for the host's uid and the session's group (the runner's too), nothing for others (0660). */
private const val SOCKET_MODE = 0x1B0

/** rw for all (0666): the checks' socket, in a directory only the agent's and the runner's containers mount. */
private const val OPEN_MODE = 0x1B6

/** The exit code of a bad configuration (1 is an execution the server ended). */
internal const val EXIT_BAD_CONFIGURATION = 2
