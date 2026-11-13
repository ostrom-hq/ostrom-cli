// SPDX-License-Identifier: Apache-2.0
package run.ostrom.cli

import run.ostrom.cli.launcher.WORKFLOWS_DELIVER
import run.ostrom.cli.launcher.adapters.SessionLayout
import run.ostrom.core.api.models.ServerFeatures
import kotlin.test.Test
import kotlin.test.assertEquals

/** The launcher depends on no other module: its names of a step's delivery are checked against ostrom-core's. */
class WorkflowDeliverFeatureTest {
    @Test
    fun theLauncherNamesTheDeliveryAsTheServerDoes() {
        assertEquals(ServerFeatures.WORKFLOWS_DELIVER, WORKFLOWS_DELIVER)
        assertEquals("ostrom-workflow", SessionLayout.WORKFLOW_SERVER)
    }
}
