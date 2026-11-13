// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli.todo

import run.ostrom.core.api.models.TodoItemOrigin
import run.ostrom.core.api.models.TodoItemStatus
import run.ostrom.core.api.models.TodoProof
import run.ostrom.core.api.models.TodoStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class TodoRenderTest {
    private fun proof(text: String) = TodoProof(AT, true, text)

    @Test
    fun theReferenceComesFirstThenTheNextItemInFullThenOneLinePerItem() {
        val list = todo(
            item("plan", status = TodoItemStatus.DONE, proof = proof("PR #3 MERGED")),
            item("bare-done", status = TodoItemStatus.DONE),
            item("gone", status = TodoItemStatus.DROPPED),
            item("asked", status = TodoItemStatus.DROP_REQUESTED),
            item("doing", status = TodoItemStatus.DOING, origin = TodoItemOrigin.DISCOVERED),
            item("next", dependsOn = listOf("plan"), proof = TodoProof(AT, false, "PR #4 is OPEN")),
            item("later", dependsOn = listOf("next"), unverified = true),
            item("free"),
            next = "next",
        )
        assertEquals(
            """
            [Ostrom todo] 2 of 8 items done (active).
            Plan reference:
              The plan: https://ostrom.example/artifact/1
            Next item: next — Name of next
              What next is about
              Done when: Done when next holds
              Last check: PR #4 is OPEN
            Items:
              ✓ plan                   Name of plan  PR #3 MERGED
              ✓ bare-done              Name of bare-done
              x gone                   Name of gone
              ? asked                  Name of asked  waiting for the owner to agree to drop it
              → doing                  Name of doing [added on the way]
              → next                   Name of next
              · later                  Name of later (attested)  (after next)
              · free                   Name of free
            """.trimIndent(),
            TodoRender.show(list),
        )
    }

    @Test
    fun aListWithoutNextItemOrItsLastCheckSaysSo() {
        val finished = todo(item("only", status = TodoItemStatus.DONE), status = TodoStatus.DONE)
        assertEquals(
            """
            [Ostrom todo] 1 of 1 items done (done).
            Plan reference:
              The plan: https://ostrom.example/artifact/1
            Items:
              ✓ only                   Name of only
            """.trimIndent(),
            TodoRender.show(finished),
        )
        val fresh = TodoRender.show(todo(item("first"), next = "first"))
        assertEquals(true, fresh.contains("  Done when: Done when first holds\nItems:"), fresh)
    }
}
