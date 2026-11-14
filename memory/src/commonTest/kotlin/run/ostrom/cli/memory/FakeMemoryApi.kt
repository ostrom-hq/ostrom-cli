// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.memory

import run.ostrom.core.api.models.CreateMemoryRequest
import run.ostrom.core.api.models.MemoryEntry
import run.ostrom.core.api.models.MemoryIndexEntry
import run.ostrom.core.api.models.MemoryIndexPage
import run.ostrom.core.api.models.MemorySearchResult
import run.ostrom.core.api.models.UpdateMemoryRequest

/** An index entry of the tests. */
fun indexEntry(id: String, title: String = "Title $id", tags: List<String> = emptyList()) = MemoryIndexEntry(
    id = id,
    scope = "personal",
    title = title,
    summary = "Summary of $id",
    tags = tags,
    updatedAt = "2026-11-14T10:00:00Z",
)

/** A whole entry of the tests. */
fun memoryEntry(id: String = "m-1", version: Int = 1, status: String = "active") = MemoryEntry(
    id = id,
    scope = "personal",
    title = "Title",
    summary = "Summary",
    content = "Content",
    status = status,
    origin = "agent",
    authorId = "u-1",
    version = version,
    createdAt = "2026-11-14T10:00:00Z",
    updatedAt = "2026-11-14T10:00:00Z",
)

/** Ostrom as the tools see it: answers what the test sets, records every call, refuses with [refusal] when set. */
class FakeMemoryApi : MemoryApi {
    val calls = mutableListOf<String>()
    var page = MemoryIndexPage(listOf(indexEntry("m-1")))
    var found = MemorySearchResult(listOf(indexEntry("m-2")))
    var entry = memoryEntry()
    var refusal: MemoryApiException? = null
    var created: CreateMemoryRequest? = null
    var updated: UpdateMemoryRequest? = null

    private fun record(call: String) {
        calls += call
        refusal?.let { throw it }
    }

    override fun index(scope: String?, tags: List<String>, after: String?, limit: Int): MemoryIndexPage {
        record("index $scope $tags $after $limit")
        return page
    }

    override fun search(query: String, tags: List<String>, limit: Int): MemorySearchResult {
        record("search $query $tags $limit")
        return found
    }

    override fun read(id: String): MemoryEntry {
        record("read $id")
        return entry
    }

    override fun create(request: CreateMemoryRequest): MemoryEntry {
        record("create")
        created = request
        return entry
    }

    override fun update(id: String, request: UpdateMemoryRequest): MemoryEntry {
        record("update $id")
        updated = request
        return entry
    }

    override fun forget(id: String) {
        record("forget $id")
    }
}
