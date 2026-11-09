// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.update

/**
 * Release manifests signed by a TEST key derived from a public seed (`SHA-256("ostrom-cli update test key (public
 * seed, signs nothing else)")`), written by a throwaway script with Python's `cryptography`: Ed25519 over
 * the exact bytes, as `scripts/release/sign-manifest.sh` signs `latest.json`.
 */
internal object ReleaseVectors {
    const val KEY_PEM = "-----BEGIN PUBLIC KEY-----\n" +
        "MCowBQYDK2VwAyEAJRX8qPhyoVwEKp1vnphQX3TQqVniQ/zuKQ/DjENVnjc=\n" +
        "-----END PUBLIC KEY-----\n"

    /** The archive every manifest names. */
    const val ARCHIVE = "fake ostrom release archive"

    /** Each manifest and its signature (base64 of the raw 64 bytes, then a newline, as the release writes it). */
    val MANIFESTS: Map<String, Pair<String, String>> = mapOf(
        "LATEST" to (
            "{\"version\":\"9.9.9\",\"date\":\"2026-10-30T10:00:00Z\",\"assets\":{\"linux-x64\":{\"url\":\"https" +
            "://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"sha256\":\"9a1f0d88" +
            "0c449c72547b77ddfa22e96ad5ee677ef6c05c7b31ce43e3798bb5a3\",\"size\":27}}}" to
                "Irv2Rjp64IA5n5S4gokB4mmcz6BBAEyiKDMjzl4jRFTLHznDCLJ5IQznFWR9lHaDC/DcpjCd1aBesU9oXsH/BQ==\n"
        ),
        "LATEST_FLOOR" to (
            "{\"version\":\"9.9.9\",\"floor\":\"1.0.0\",\"date\":\"2026-10-30T10:00:00Z\"," +
            "\"assets\":{\"linux-x64\":{\"url\":\"https" +
            "://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"sha256\":\"9a1f0d88" +
            "0c449c72547b77ddfa22e96ad5ee677ef6c05c7b31ce43e3798bb5a3\",\"size\":27}}}" to
                "K4KxaKbIgU8zp+uAuHNNaC2+7oCObZc0F2WX/4O+JIgfQYPZ1X6rxrX6yKpJvbqEkQjl/LL2WOoBFXAgPceRDA==\n"
        ),
        "LATEST_LOW_FLOOR" to (
            "{\"version\":\"9.9.9\",\"floor\":\"0.5.0\",\"date\":\"2026-10-30T10:00:00Z\"," +
            "\"assets\":{\"linux-x64\":{\"url\":\"https" +
            "://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"sha256\":\"9a1f0d88" +
            "0c449c72547b77ddfa22e96ad5ee677ef6c05c7b31ce43e3798bb5a3\",\"size\":27}}}" to
                "lWUzVOCuimQPcvE4eT2fpOOfjexmoVmIl4VmY6A9ReL9Pw4nwPJLCaIwf/DWiMOKRIDXEttYY45V8EAcekABCg==\n"
        ),
        "SAME" to (
            "{\"version\":\"1.0.0\",\"date\":\"2026-10-30T10:00:00Z\",\"assets\":{\"linux-x64\":{\"url\":\"https" +
            "://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"sha256\":\"9a1f0d88" +
            "0c449c72547b77ddfa22e96ad5ee677ef6c05c7b31ce43e3798bb5a3\",\"size\":27}}}" to
                "mtnOM3J69uZJo4WQnkbiOAtIawcun5taqAVMnZrROxrY9ohBFvm8qjs+2ULpQEVJ5GjWQJbYvna+1zFgDLgTCg==\n"
        ),
        "OLDER" to (
            "{\"version\":\"0.9.0\",\"date\":\"2026-10-30T10:00:00Z\",\"assets\":{\"linux-x64\":{\"url\":\"https" +
            "://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"sha256\":\"9a1f0d88" +
            "0c449c72547b77ddfa22e96ad5ee677ef6c05c7b31ce43e3798bb5a3\",\"size\":27}}}" to
                "ctBlNNu8X/CEzurWWGnngpTKgo11G9pTvMa+1pyRzWOWqlR+dBlPH5MYZSREYqNyOFQxshNgf28RwHymIHoCBw==\n"
        ),
        "NOT_JSON" to (
            "not json" to
                "l1NoR1C9K61yttpIMS3/SLAO+F1mcgFdfUP4UJ+4vsz+hIPxT3ZU6p7jzUuZk51/LRkArJzPfOlZQrk/NczvDA==\n"
        ),
        "NO_VERSION" to (
            "{\"date\":\"2026-10-30T10:00:00Z\",\"assets\":{\"linux-x64\":{\"url\":\"https://releases.exampl" +
            "e/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"sha256\":\"9a1f0d880c449c72547b77ddfa" +
            "22e96ad5ee677ef6c05c7b31ce43e3798bb5a3\",\"size\":27}}}" to
                "YmJj4rL5/QQP4vxy5Ke5yNdCSMbhoLlzZRCcwXI6BwDk9Ef6wbO/0WQA8HfP1XQDrDjEtUzFyMyl9jDmr8FUDQ==\n"
        ),
        "NO_ASSETS" to (
            "{\"version\":\"9.9.9\",\"date\":\"2026-10-30T10:00:00Z\"}" to
                "mQCxfeNTMogv2vR1KcSd7R9u7UnlnikpjO9NmOvZKRWi4DNBxmmCHkeSZMMHzwMEREUWxZR1cLCbsQdZLaSHBw==\n"
        ),
        "OTHER_TARGET" to (
            "{\"version\":\"9.9.9\",\"date\":\"2026-10-30T10:00:00Z\",\"assets\":{\"macos-arm64\":{\"url\":\"htt" +
            "ps://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"sha256\":\"9a1f0d" +
            "880c449c72547b77ddfa22e96ad5ee677ef6c05c7b31ce43e3798bb5a3\",\"size\":27}}}" to
                "Uf0IG1pOqb4fG8I9gSQefssqmiuXd01P+x/pKqgQQP3WdmjNB95fFeq01ru8su3UrQYTqKnhVQXYAqToqJW7Ag==\n"
        ),
        "NO_URL" to (
            "{\"version\":\"9.9.9\",\"date\":\"2026-10-30T10:00:00Z\",\"assets\":{\"linux-x64\":{\"sha256\":\"9a" +
            "1f0d880c449c72547b77ddfa22e96ad5ee677ef6c05c7b31ce43e3798bb5a3\",\"size\":27}}}" to
                "dWwaUSV/nbNsR/zii50qE7rqsuOwm0dCZn5gqQ82bZ2I4hRYuT/JQ0Ovb0tA2A/pOSdxz2tbGLGfLNtcN7aeCQ==\n"
        ),
        "NO_SHA" to (
            "{\"version\":\"9.9.9\",\"date\":\"2026-10-30T10:00:00Z\",\"assets\":{\"linux-x64\":{\"url\":\"https" +
            "://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"size\":27}}}" to
                "+ZGIMg97PaU1QXhm/FwuhHMy4s8Z4KQuvPY/G6hmlqV8s1L/CqIC9cGUQoqejQdqZ7wDYA5itEyuoSXLzvQHDg==\n"
        ),
        "NO_SIZE" to (
            "{\"version\":\"9.9.9\",\"date\":\"2026-10-30T10:00:00Z\",\"assets\":{\"linux-x64\":{\"url\":\"https" +
            "://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"sha256\":\"9a1f0d88" +
            "0c449c72547b77ddfa22e96ad5ee677ef6c05c7b31ce43e3798bb5a3\"}}}" to
                "7fu87W0CqxDjzHfnkSH4QuUd7FfJQoO1hcZdS4UGu94c17PX47KFMaxn6nwFTy39B7+A1ED57kyN1NdXQ3vMDQ==\n"
        ),
        "WRONG_SIZE" to (
            "{\"version\":\"9.9.9\",\"date\":\"2026-10-30T10:00:00Z\",\"assets\":{\"linux-x64\":{\"url\":\"https" +
            "://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"sha256\":\"9a1f0d88" +
            "0c449c72547b77ddfa22e96ad5ee677ef6c05c7b31ce43e3798bb5a3\",\"size\":1}}}" to
                "8ooc3q1YuSI+S11lWQ8Kwb+jl0DihZCfxdodXHORcEMsYax2L1+CWJXuMRk1GC3H1GKlmVSmqXd1h9xWbJ8vAA==\n"
        ),
        "WRONG_SHA" to (
            "{\"version\":\"9.9.9\",\"date\":\"2026-10-30T10:00:00Z\",\"assets\":{\"linux-x64\":{\"url\":\"https" +
            "://releases.example/download/9.9.9/ostrom-9.9.9-linux-x64.tar.gz\",\"sha256\":\"00000000" +
            "00000000000000000000000000000000000000000000000000000000\",\"size\":27}}}" to
                "kyUiIs2sI8HgjmRCl2Wenk/lzCuWPP/sa2st5VlgnXhMpvzxJzZ3ymDQH1JMQiHb+1Vr7QxDmRayt+Jql/DxAg==\n"
        ),
    )
}
