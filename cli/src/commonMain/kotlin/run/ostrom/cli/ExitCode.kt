// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

/**
 * The exit codes of `ostrom` (spec CLI.md § 2.3). 64 to 78 follow `sysexits.h`, like the prototype launcher.
 * `ostrom <agent>` returns the agent's own code once it has started. `ostrom help exit-codes` prints the table, the
 * same as `docs/MODULES.md` (ExitCodesTest).
 *
 * @property meaning What the code tells a script, as `ostrom help exit-codes` prints it.
 */
@Suppress("MagicNumber") // The numbers are the table of the spec, named by the entries themselves.
enum class ExitCode(val code: Int, val meaning: String) {
    OK(0, "Done."),

    /** The check asked for failed (`verify` invalid, `doctor` with a ✗). */
    FAILED(1, "The check asked for failed: verify found the receipt invalid, doctor found a problem."),

    /** Incomplete, cancelled, or a no. */
    INCOMPLETE(2, "Incomplete, cancelled, or a no."),

    /** Bad usage: unknown option or agent, an option this version does not have yet, a question without a terminal. */
    USAGE(
        64,
        "Bad usage: an unknown command, option or agent, an option not in this version, a question with no terminal.",
    ),

    /** Sign-in required. */
    SIGN_IN_REQUIRED(65, "Sign-in required: run ostrom login."),

    /** Not found: receipt, gate, harness, not a git repository. */
    NOT_FOUND(66, "Not found: a receipt, a gate, a harness, or not a git repository."),

    /** The server is unreachable. */
    UNREACHABLE(69, "The server is unreachable, or the secret store did not answer."),

    /** The command is not implemented yet in this version (EX_SOFTWARE); disappears as commands land. */
    NOT_IMPLEMENTED(70, "The command is not implemented in this version yet."),

    /** A local file could not be written. */
    CANNOT_WRITE(73, "A local file could not be written."),

    /** Still waiting (`wait` at its deadline), or a governance change nobody confirmed in time. */
    STILL_WAITING(75, "Still waiting: wait reached its deadline, or nobody confirmed a governance change in time."),

    /** Refused: policy, role, self-approval, other organisation. */
    REFUSED(77, "Refused: the policy, your role, a self-approval, another organisation."),

    /** Signature or package refused. */
    SIGNATURE_REFUSED(78, "A signature or a package was refused."),

    /** The agent is not installed. */
    AGENT_MISSING(127, "The agent is not installed."),
}
