package com.galaxyssi.chat

import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class AspectRatioVideoViewDeviceTest {
    @Test fun fillsAvailableWidthAndPreservesLandscapeAndPortraitRatios() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val video = AspectRatioVideoView(instrumentation.targetContext)
            fun measure(width: Int, expectedHeight: Int) {
                video.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertEquals(width, video.measuredWidth)
                assertEquals(expectedHeight, video.measuredHeight)
            }
            video.setVideoDimensions(426, 240)
            measure(1144, 645)
            measure(600, 338)
            video.setVideoDimensions(540, 960)
            measure(600, 1067)
            video.setVideoDimensions(0, 0)
            measure(600, 1067)
            video.setVideoDimensions(512, 512)
            measure(600, 600)
        }
    }
}
