// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli

/**
 * The exit codes of `ostrom` (spec CLI.md § 2.3). 64 to 78 follow `sysexits.h`, like the prototype launcher.
 * `ostrom <agent>` returns the agent's own code once it has started.
 */
@Suppress("MagicNumber") // The numbers are the table of the spec, named by the entries themselves.
enum class ExitCode(val code: Int) {
    OK(0),

    /** The check asked for failed (`verify` invalid, `doctor` with a ✗). */
    FAILED(1),

    /** Incomplete, cancelled, or a no. */
    INCOMPLETE(2),

    /** Bad usage: unknown option or agent, a question without a terminal. */
    USAGE(64),

    /** Sign-in required. */
    SIGN_IN_REQUIRED(65),

    /** Not found: receipt, gate, harness, not a git repository. */
    NOT_FOUND(66),

    /** The server is unreachable. */
    UNREACHABLE(69),

    /** The command is not implemented yet in this version (EX_SOFTWARE); disappears as commands land. */
    NOT_IMPLEMENTED(70),

    /** A local file could not be written. */
    CANNOT_WRITE(73),

    /** Still waiting (`wait` at its deadline). */
    STILL_WAITING(75),

    /** Refused: policy, role, self-approval, other organisation. */
    REFUSED(77),

    /** Signature or package refused. */
    SIGNATURE_REFUSED(78),

    /** The agent is not installed. */
    AGENT_MISSING(127),
}
