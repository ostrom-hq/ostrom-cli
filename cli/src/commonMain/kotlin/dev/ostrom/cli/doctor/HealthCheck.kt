// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.doctor

import dev.ostrom.cli.api.ServerResponse

/** The verdict on the answer of the health route. */
@Suppress("UnusedParameter") // red first: the body and the version are not read yet
internal fun healthCheck(host: String, url: String, response: ServerResponse, contract: String): DoctorCheck =
    if (response.status < SERVER_ERROR) {
        DoctorCheck(CheckStatus.OK, "$host reachable (${response.millis} ms)")
    } else {
        DoctorCheck(CheckStatus.PROBLEM, "$host answers ${response.status}: the server is in trouble, try again later.")
    }

/** The first HTTP status that means the server itself failed. */
private const val SERVER_ERROR = 500
