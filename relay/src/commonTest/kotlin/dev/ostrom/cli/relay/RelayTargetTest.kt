// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.cli.relay

import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayTargetTest {
    private val config = relayConfig(inference = true)

    @Test
    fun everyHookRouteOfTheAllowListIsAHook() {
        val posts = listOf(
            "/api/v1/sessions",
            "/api/v1/sessions/s-1/reopen",
            "/api/v1/sessions/s-1/seen",
            "/api/v1/gates",
            "/api/v1/notifications",
        )
        for (path in posts) {
            assertEquals(RelayTarget.Hook(longPoll = false), resolveTarget(HttpMethod.Post, path, config), path)
        }
        assertEquals(RelayTarget.Hook(longPoll = false), resolveTarget(HttpMethod.Get, "/api/v1/gates/catalog", config))
        val signed = resolveTarget(HttpMethod.Get, "/api/v1/gates/catalog/signed", config)
        assertEquals(RelayTarget.SignedCatalog, signed)
        assertEquals(RelayTarget.NotFound, resolveTarget(HttpMethod.Post, "/api/v1/gates/catalog/signed", config))
        assertEquals(RelayTarget.Hook(longPoll = true), resolveTarget(HttpMethod.Get, "/api/v1/gates/g-1", config))
    }

    @Test
    fun anythingElseUnderTheApiIsNotFound() {
        val refused = listOf(
            HttpMethod.Post to "/api/v1/launches",
            HttpMethod.Post to "/api/v1/sessions/s-1/delete",
            // S2-25, S2-29: the agent can neither end its session nor use a yes up
            HttpMethod.Post to "/api/v1/sessions/s-1/end",
            HttpMethod.Post to "/api/v1/sessions/s-1/alive",
            HttpMethod.Post to "/api/v1/sessions/s-1/workflow-receipt",
            HttpMethod.Post to "/api/v1/gates/g_1/consume",
            HttpMethod.Post to "/api/v1/gates/g_1/reserve",
            HttpMethod.Post to "/api/v1/gates/g_1/release",
            HttpMethod.Post to "/api/v1/checks",
            HttpMethod.Post to "/api/v1/gates/catalog",
            HttpMethod.Post to "/api/v1/gates/a.b/consume",
            HttpMethod.Post to "/api/v1/sessions/",
            HttpMethod.Get to "/api/v1/gates",
            HttpMethod.Get to "/api/v1/gates/../launches",
            HttpMethod.Get to "/api/v1/gates/g-1/consume",
            HttpMethod.Get to "/api/v1/sessions",
            HttpMethod.Put to "/api/v1/sessions",
            HttpMethod.Delete to "/api/v1/gates/g-1",
        )
        for ((method, path) in refused) {
            assertEquals(RelayTarget.NotFound, resolveTarget(method, path, config), "${method.value} $path")
        }
    }

    @Test
    fun thePackagesCatalogueIsAnsweredByTheRelayItself() {
        val packaged = relayConfig(gateCatalog = """{"actions":[]}""")
        val catalog = resolveTarget(HttpMethod.Get, "/api/v1/gates/catalog", packaged)
        assertEquals(RelayTarget.Catalog("""{"actions":[]}"""), catalog)
        assertEquals(RelayTarget.NotFound, resolveTarget(HttpMethod.Post, "/api/v1/gates/catalog", packaged))
    }

    @Test
    fun mcpReachesOnlyTheAllowedServersAndOnlyByPost() {
        assertEquals(RelayTarget.Mcp("github"), resolveTarget(HttpMethod.Post, "/mcp/github", config))
        assertEquals(RelayTarget.McpMethodNotAllowed, resolveTarget(HttpMethod.Get, "/mcp/github", config))
        assertEquals(RelayTarget.McpMethodNotAllowed, resolveTarget(HttpMethod.Delete, "/mcp/github", config))
        assertEquals(RelayTarget.NotFound, resolveTarget(HttpMethod.Post, "/mcp/gitlab", config), "Not assigned")
        val badNames = relayConfig(mcpServers = setOf("-bad", "a/b", ""))
        for (name in listOf("-bad", "a/b", "")) {
            assertEquals(RelayTarget.NotFound, resolveTarget(HttpMethod.Post, "/mcp/$name", badNames), name)
        }
    }

    @Test
    fun inferenceRoutesExistOnlyWhenTheLaunchRelaysInference() {
        assertEquals(
            RelayTarget.Inference("/api/v1/inference/v1/messages"),
            resolveTarget(HttpMethod.Post, "/v1/messages", config),
        )
        assertEquals(
            RelayTarget.Inference("/api/v1/inference/v1/chat/completions"),
            resolveTarget(HttpMethod.Post, "/v1/chat/completions", config),
        )
        assertEquals(
            RelayTarget.Inference("/api/v1/inference/v1/models"),
            resolveTarget(HttpMethod.Get, "/v1/models", config),
        )
        assertEquals(RelayTarget.NotFound, resolveTarget(HttpMethod.Get, "/v1/messages", config))
        assertEquals(RelayTarget.NotFound, resolveTarget(HttpMethod.Post, "/v1/responses", config))
        val off = relayConfig(inference = false)
        for ((method, path) in listOf(HttpMethod.Post to "/v1/messages", HttpMethod.Get to "/v1/models")) {
            assertEquals(RelayTarget.NotFound, resolveTarget(method, path, off), path)
        }
    }
}
