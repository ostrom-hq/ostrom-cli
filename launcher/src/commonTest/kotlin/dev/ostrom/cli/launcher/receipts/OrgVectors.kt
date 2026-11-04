// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.launcher.receipts

/**
 * A gate receipt bound to its organisation (`orgId`, with `launchId` and `sessionId`), as ostrom-server signs every
 * receipt since server#327, signed by the TEST key of ostrom-core `compat/vectors/neutral/key.json` ([compatKey]).
 */
internal object OrgVectors {
    /** The organisation it was signed for. */
    const val ORG_ID = "7b1e4c2a-5d3f-4a6b-9c8d-1e2f3a4b5c6d"

    /** The receipt, in canonical form. */
    val GATE: String = listOf(
        "{\"agent\":\"claude-code\",\"createdAt\":\"2026-11-03T22:00:01.000Z\",\"decisions\":[{\"approvalId\":\"",
        "appr_org-0001\",\"at\":\"2026-11-03T22:00:00.000Z\",\"by\":\"lena\",\"result\":\"approved\"}],\"id\":\"rcp",
        "t_org-gate\",\"initiator\":\"maya\",\"launchId\":\"8c0f6b1e-2a4d-4e9b-a1c3-5d7e9f0a2b4c\",\"orgId\":\"",
        "7b1e4c2a-5d3f-4a6b-9c8d-1e2f3a4b5c6d\",\"rules\":[],\"sessionId\":\"sess-org\",\"signature\":\"ed255",
        "19;kid=b918e282a924dda0;sha256=a11ed6b4003cc60210c6d9c669ba3c3138670dd05a81e6af329a4a511c2",
        "f91ab;sig=mpb+bMh1gtjwpBifphBF8w28+W+4x9ioCoFw8nq5l5+sLSImCoxpnQLsFOjMFgU/IkRMvREcmiW97J+Z",
        "RyX4Cg==\",\"subject\":{\"digest\":\"0de9f18b76ea8b9808830a006d98a73fe23c89c160b6ab3ccab0a89bf27",
        "d12c6\",\"id\":\"G-org\",\"kind\":\"gate\"}}",
    ).joinToString("")
}
