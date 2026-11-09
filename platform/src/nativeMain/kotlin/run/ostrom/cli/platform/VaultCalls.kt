// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package run.ostrom.cli.platform

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/** Calls a C shim that reads a secret into a buffer of [MAX_SECRET_BYTES], with its status, length and code. */
internal fun vaultRead(call: (CPointer<UByteVar>, Int, CPointer<IntVar>, CPointer<IntVar>) -> Int): VaultAnswer =
    memScoped {
        val buffer = ByteArray(MAX_SECRET_BYTES)
        val length = alloc<IntVar>()
        val code = alloc<IntVar>()
        val status = buffer.usePinned { call(it.addressOf(0).reinterpret(), buffer.size, length.ptr, code.ptr) }
        VaultAnswer(status, code.value, buffer.copyOf(length.value))
    }

/** Calls a C shim that only answers a status and a code. */
internal fun vaultCall(call: (CPointer<IntVar>) -> Int): VaultAnswer = memScoped {
    val code = alloc<IntVar>()
    val status = call(code.ptr)
    VaultAnswer(status, code.value)
}

/** Calls a C shim that writes [secret], with its status and code. */
internal fun vaultWrite(secret: ByteArray, call: (CPointer<UByteVar>, Int, CPointer<IntVar>) -> Int): VaultAnswer =
    memScoped {
        val code = alloc<IntVar>()
        // A pinned copy one byte longer: an empty secret still has an address, and C text gets its NUL.
        val bytes = secret.copyOf(secret.size + 1)
        val status = bytes.usePinned { call(it.addressOf(0).reinterpret(), secret.size, code.ptr) }
        VaultAnswer(status, code.value)
    }
