// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.ui

/**
 * Desktop manifests signed by the TEST key of `ReleaseVectors` (a public seed, signs nothing else) with
 * `e2e/ed25519.py`, the way `desktop-manifest.json.sig` is signed: Ed25519 over the exact bytes, base64. Every
 * manifest lists the package [PACKAGE] for linux-x64, macos-arm64 and windows-x64.
 */
internal object DesktopVectors {
    /** The bytes of every package the manifests sign. */
    const val PACKAGE = "fake desktop package"

    /** Each manifest and its signature. */
    val MANIFESTS: Map<String, Pair<String, String>> = mapOf(
        "DESKTOP" to (
            "{\"assets\":{\"linux-x64\":{\"fileName\":\"ostrom-desktop-1.2.3-linux-x64.tar.gz\",\"sha256\":\"d256" +
            "1f18e18dbbbbd3901c9b166b040f2720123cfa543a10915e5edb9514f7fe\",\"size\":20},\"macos-arm64\":{\"fileN" +
            "ame\":\"ostrom-desktop-1.2.3-macos-arm64.zip\",\"sha256\":\"d2561f18e18dbbbbd3901c9b166b040f2720123c" +
            "fa543a10915e5edb9514f7fe\",\"size\":20},\"windows-x64\":{\"fileName\":\"ostrom-desktop-1.2.3-windows" +
            "-x64.msi\",\"sha256\":\"d2561f18e18dbbbbd3901c9b166b040f2720123cfa543a10915e5edb9514f7fe\",\"size\":" +
            "20}},\"product\":\"desktop\",\"schemaVersion\":1,\"sourceCommit\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
            "bbbbbbbb\",\"tag\":\"1.2.3\",\"version\":\"1.2.3\"}" to
                "JU5YybrVXNwg3epkcmb688Qeie2ckl/FGN928xXzhMMa1p9qHWhE1NgVDOGqJ4EsGxoTrFT1kME65Xl7MuDJBQ==\n"
        ),
        "OTHER_PRODUCT" to (
            "{\"assets\":{\"linux-x64\":{\"fileName\":\"ostrom-desktop-1.2.3-linux-x64.tar.gz\",\"sha256\":\"d256" +
            "1f18e18dbbbbd3901c9b166b040f2720123cfa543a10915e5edb9514f7fe\",\"size\":20},\"macos-arm64\":{\"fileN" +
            "ame\":\"ostrom-desktop-1.2.3-macos-arm64.zip\",\"sha256\":\"d2561f18e18dbbbbd3901c9b166b040f2720123c" +
            "fa543a10915e5edb9514f7fe\",\"size\":20},\"windows-x64\":{\"fileName\":\"ostrom-desktop-1.2.3-windows" +
            "-x64.msi\",\"sha256\":\"d2561f18e18dbbbbd3901c9b166b040f2720123cfa543a10915e5edb9514f7fe\",\"size\":" +
            "20}},\"product\":\"cli\",\"schemaVersion\":1,\"sourceCommit\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
            "bbbb\",\"tag\":\"1.2.3\",\"version\":\"1.2.3\"}" to
                "rS6AQdmXry9+Hy+bTZIN0iizY+upIpSSbgdl5st/jy29aj6I0A6ir2UoAlDHHCFVlUiCoMVNUJ1JCkNU6OfnCQ==\n"
        ),
        "BAD_NAME" to (
            "{\"assets\":{\"linux-x64\":{\"fileName\":\"evil.tar.gz\",\"sha256\":\"d2561f18e18dbbbbd3901c9b166b04" +
            "0f2720123cfa543a10915e5edb9514f7fe\",\"size\":20},\"macos-arm64\":{\"fileName\":\"ostrom-desktop-1.2" +
            ".3-macos-arm64.zip\",\"sha256\":\"d2561f18e18dbbbbd3901c9b166b040f2720123cfa543a10915e5edb9514f7fe\"" +
            ",\"size\":20},\"windows-x64\":{\"fileName\":\"ostrom-desktop-1.2.3-windows-x64.msi\",\"sha256\":\"d2" +
            "561f18e18dbbbbd3901c9b166b040f2720123cfa543a10915e5edb9514f7fe\",\"size\":20}},\"product\":\"desktop" +
            "\",\"schemaVersion\":1,\"sourceCommit\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"tag\":\"1.2.3" +
            "\",\"version\":\"1.2.3\"}" to
                "3Uvc5cui036BFIWUFP4Ho0i2cA93x8FiCcYXK/t5i5g3jPe9k67gER0eGIF3LKzxkJukJ4CmY4aD1WlHZI/fAQ==\n"
        ),
        "VERSION_124" to (
            "{\"assets\":{\"linux-x64\":{\"fileName\":\"ostrom-desktop-1.2.4-linux-x64.tar.gz\",\"sha256\":\"d256" +
            "1f18e18dbbbbd3901c9b166b040f2720123cfa543a10915e5edb9514f7fe\",\"size\":20},\"macos-arm64\":{\"fileN" +
            "ame\":\"ostrom-desktop-1.2.4-macos-arm64.zip\",\"sha256\":\"d2561f18e18dbbbbd3901c9b166b040f2720123c" +
            "fa543a10915e5edb9514f7fe\",\"size\":20},\"windows-x64\":{\"fileName\":\"ostrom-desktop-1.2.4-windows" +
            "-x64.msi\",\"sha256\":\"d2561f18e18dbbbbd3901c9b166b040f2720123cfa543a10915e5edb9514f7fe\",\"size\":" +
            "20}},\"product\":\"desktop\",\"schemaVersion\":1,\"sourceCommit\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
            "bbbbbbbb\",\"tag\":\"1.2.4\",\"version\":\"1.2.4\"}" to
                "f1jq/JRW5ds27gB25t6i3NPPPxEU5KwoRN10rFDYG+8cg4ITuvTbxb8lNtDUJwMf5YPRyZi5toYmkHow+zNyAA==\n"
        ),
        "BAD_SCHEMA" to (
            "{\"assets\":{\"linux-x64\":{\"fileName\":\"ostrom-desktop-1.2.3-linux-x64.tar.gz\",\"sha256\":\"d256" +
            "1f18e18dbbbbd3901c9b166b040f2720123cfa543a10915e5edb9514f7fe\",\"size\":20},\"macos-arm64\":{\"fileN" +
            "ame\":\"ostrom-desktop-1.2.3-macos-arm64.zip\",\"sha256\":\"d2561f18e18dbbbbd3901c9b166b040f2720123c" +
            "fa543a10915e5edb9514f7fe\",\"size\":20},\"windows-x64\":{\"fileName\":\"ostrom-desktop-1.2.3-windows" +
            "-x64.msi\",\"sha256\":\"d2561f18e18dbbbbd3901c9b166b040f2720123cfa543a10915e5edb9514f7fe\",\"size\":" +
            "20}},\"product\":\"desktop\",\"schemaVersion\":2,\"sourceCommit\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
            "bbbbbbbb\",\"tag\":\"1.2.3\",\"version\":\"1.2.3\"}" to
                "2LIswRU8be1Q+AR9S2B0i0GCpw3ucBMlQfYjWW6qCUG/aZLL/cCKzeLe2u6AOjaX9C3Vjui+9xceZwbzR0KzBg==\n"
        ),
    )
}
