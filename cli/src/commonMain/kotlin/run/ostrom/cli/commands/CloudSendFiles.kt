// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0): cli/src/commonMain/kotlin/me/nathanfallet/teamcode/cli/cloud/AttachFiles.kt
// (the policy, the refusals and their words) and
// client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/CloudFileUpload.kt (the retries and the integrity
// check), for `ostrom cloud send --file`.
package run.ostrom.cli.commands

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.toByteString
import run.ostrom.cli.CliDependencies
import run.ostrom.cli.ExitCode
import run.ostrom.cli.launcher.ServerAnswer
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.CloudFile
import run.ostrom.core.api.models.CloudFilePolicy
import run.ostrom.core.api.models.CloudFiles
import run.ostrom.core.api.models.ServerFeatures
import kotlin.uuid.Uuid

/**
 * Checks the files of `cloud send --file` against the organization's policy, then uploads each (one
 * `Idempotency-Key` per file across its retries) and returns their ids, in order. Every file is read and checked before
 * the first upload, so a refused file leaves nothing behind on the server.
 */
internal suspend fun CliDependencies.uploadFiles(
    access: OrgAccess,
    session: String,
    paths: List<String>,
): List<String> {
    if (paths.isEmpty()) return emptyList()
    if (paths.size > CloudFiles.MAX_FILES_PER_MESSAGE) refuseFile(tooMany(CloudFiles.MAX_FILES_PER_MESSAGE))
    requireFeature(access.signed.serverUrl, access.signed.token, ServerFeatures.RUNNERS_CLOUD_FILES, NO_FILES)
    val read = access.server.get(FILES_POLICY, access.signed.token)
    val policy = policyOf(read.orStop(access.signed.serverUrl, NO_FILES))
    if (!policy.available) halt(ExitCode.REFUSED, FILES_OFF)
    if (paths.size > policy.maxFilesPerMessage) refuseFile(tooMany(policy.maxFilesPerMessage))
    val files = paths.map { readLocalFile(it, policy) }
    val total = files.sumOf { it.bytes.size.toLong() }
    if (total > policy.maxMessageBytes) {
        refuseFile(
            "These files together are ${sizeLabel(total)}; the organization allows " +
                "${sizeLabel(policy.maxMessageBytes)} per message.",
        )
    }
    return files.map { upload(access, session, it, policy).id }
}

/** What a refusal of a message carrying files says, or null when it is not about its files. */
internal fun filesRefusal(type: String?): String? = when (type) {
    CloudFiles.FILE_UNAVAILABLE ->
        "Message refused: an attached file is no longer available (sent already or expired after 24 hours)."
    CloudFiles.FILES_TOO_LARGE -> "Message refused: its files together exceed the organization's limit per message."
    CloudFiles.UNSUPPORTED -> "Message refused: this server does not take files. Update it to send files."
    "invalid_files" -> "Message refused: the attached files are not valid for this session."
    else -> null
}

private fun policyOf(body: JsonObject): CloudFilePolicy = try {
    OstromJson.decodeFromJsonElement(CloudFilePolicy.serializer(), body)
} catch (_: SerializationException) {
    halt(ExitCode.UNREACHABLE, "Ostrom could not answer: the files policy cannot be read.")
}

private fun tooMany(max: Int) = "A message carries $max files at most."

/**
 * Uploads [file] under one `Idempotency-Key`, retrying a server that was not reached, 408, 429 other than
 * `upload_quota` and 5xx, five times in all; then checks the server stored the very bytes sent.
 */
private suspend fun CliDependencies.upload(
    access: OrgAccess,
    session: String,
    file: LocalFile,
    policy: CloudFilePolicy,
): CloudFile {
    val path = "${cloudSessionPath(session)}/files?name=${segment(file.name)}"
    val key = Uuid.random().toString()
    var wait = FIRST_UPLOAD_RETRY
    var answer = access.server.upload(path, file.bytes, access.signed.token, UPLOAD_MILLIS, key)
    var attempts = 1
    while (answer is ServerAnswer.Failed && answer.retried() && attempts < UPLOAD_ATTEMPTS) {
        console.err("Uploading ${file.shown} failed; retrying.\n")
        sleep(wait)
        wait *= 2
        attempts++
        answer = access.server.upload(path, file.bytes, access.signed.token, UPLOAD_MILLIS, key)
    }
    val stored = storedOf(file, answer, policy, access.signed.serverUrl)
    val digest = file.bytes.toByteString().sha256().hex()
    if (stored.size != file.bytes.size.toLong() || !stored.sha256.equals(digest, ignoreCase = true)) {
        halt(ExitCode.FAILED, "The server stored other bytes than ${file.shown}; the message was not sent.")
    }
    val renamed = if (stored.name != file.name) " as ${stored.name.printable()}" else ""
    console.err("Uploaded ${file.shown} (${sizeLabel(stored.size)})$renamed.\n")
    return stored
}

/** The file the server stored, or the stop its refusal calls for. */
private fun storedOf(file: LocalFile, answer: ServerAnswer, policy: CloudFilePolicy, serverUrl: String): CloudFile =
    when (answer) {
        is ServerAnswer.Failed -> uploadRefusal(file.shown, answer, policy, serverUrl)
        is ServerAnswer.Ok -> try {
            OstromJson.decodeFromJsonElement(CloudFile.serializer(), answer.body)
        } catch (_: SerializationException) {
            halt(ExitCode.UNREACHABLE, "Ostrom could not answer: the upload of ${file.shown} cannot be read.")
        }
    }

private fun ServerAnswer.Failed.retried(): Boolean {
    val code = status ?: return true
    return code == TIMEOUT || (code == TOO_MANY && type != CloudFiles.UPLOAD_QUOTA) || code >= SERVER_ERRORS
}

private fun uploadRefusal(
    shown: String,
    answer: ServerAnswer.Failed,
    policy: CloudFilePolicy,
    serverUrl: String,
): Nothing =
    when (answer.type) {
        CloudFiles.FILE_TOO_LARGE -> refuseFile(tooLarge(shown, policy))
        CloudFiles.FILE_TYPE_NOT_ALLOWED -> refuseFile(notTaken(shown, policy) + "; its content decides, not its name.")
        CloudFiles.UPLOAD_QUOTA -> halt(ExitCode.REFUSED, answer.message)
        CloudFiles.STORAGE_NOT_CONFIGURED -> halt(ExitCode.REFUSED, FILES_OFF)
        CloudFiles.STORAGE_UNAVAILABLE ->
            halt(ExitCode.UNREACHABLE, "The organization's file storage did not answer; send $shown again in a moment.")
        "empty_file" -> refuseFile("$shown is empty.")
        "invalid_file_name" -> refuseFile("The server does not accept the name of $shown; rename it.")
        "idempotency_conflict" -> refuseFile("The upload of $shown conflicted with an earlier one; send it again.")
        else -> throw answer.refusal(serverUrl, "No cloud session.")
    }

private const val FILES_POLICY = "/api/v1/cloud/files/policy"

/** What `send --file` says to a server without `runners.cloud.files.v1`. */
internal const val NO_FILES =
    "This Ostrom server does not take files (it lacks runners.cloud.files.v1); it needs an update first."

/** What `send --file` says when the organization has no tested artifact storage. */
internal const val FILES_OFF =
    "Files are off for this organization: an administrator has to configure and test its artifact storage first."

private const val FIRST_UPLOAD_RETRY = 1_000L
private const val UPLOAD_ATTEMPTS = 5
private const val UPLOAD_MILLIS = 120_000L
private const val TIMEOUT = 408
private const val TOO_MANY = 429
private const val SERVER_ERRORS = 500
