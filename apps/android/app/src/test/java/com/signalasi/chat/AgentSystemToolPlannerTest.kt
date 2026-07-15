package com.signalasi.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSystemToolPlannerTest {
    @Test
    fun recognizesChinesePhoneCameraCommands() {
        assertTrue(AgentSystemToolPlanner.isCameraCaptureGoal("\u8c03\u7528\u624b\u673a\u6444\u50cf\u5934\u62cd\u7167"))
        assertTrue(AgentSystemToolPlanner.isCameraCaptureGoal("\u6253\u5f00\u76f8\u673a"))
        assertTrue(AgentSystemToolPlanner.isCameraCaptureGoal("\u62cd\u7167"))
    }

    @Test
    fun doesNotTreatCameraDiscussionAsAnAction() {
        assertFalse(AgentSystemToolPlanner.isCameraCaptureGoal("Explain how a camera sensor works"))
        assertFalse(AgentSystemToolPlanner.isCameraCaptureGoal("\u5206\u6790\u6444\u50cf\u5934\u7684\u539f\u7406"))
    }
}
