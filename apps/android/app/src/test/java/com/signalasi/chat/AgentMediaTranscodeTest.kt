package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMediaTranscodeTest {
    @Test
    fun buildsTypedPlansForEveryAdvertisedFormat() {
        val expectedCodec = mapOf(
            AgentMediaTargetFormat.MP4 to "mpeg4",
            AgentMediaTargetFormat.M4A to "aac",
            AgentMediaTargetFormat.WAV to "pcm_s16le",
            AgentMediaTargetFormat.FLAC to "flac",
            AgentMediaTargetFormat.GIF to "-loop",
            AgentMediaTargetFormat.PNG to "png",
            AgentMediaTargetFormat.JPG to "mjpeg"
        )

        AgentMediaTargetFormat.entries.forEach { format ->
            val request = request(format)
            val destination = "outputs/result.${format.extension}"
            val plan = AgentFfmpegTranscodePlanner.create(request, "inputs/source.mov", destination)

            assertEquals("inputs/source.mov", plan.sourcePath)
            assertEquals(destination, plan.destinationPath)
            assertTrue(plan.arguments.contains(expectedCodec.getValue(format)))
            assertEquals("./inputs/source.mov", plan.arguments[plan.arguments.indexOf("-i") + 1])
            assertEquals("./$destination", plan.arguments.last())
            assertFalse(plan.arguments.contains("sh"))
            assertFalse(plan.arguments.contains("-c"))
            assertFalse(plan.arguments.any { it.contains(';') || it.contains("&&") || it.contains("||") })
        }
    }

    @Test
    fun appliesBoundedTrimScaleAndAudioSettingsWithoutShellJoining() {
        val plan = AgentFfmpegTranscodePlanner.create(
            request(AgentMediaTargetFormat.MP4).copy(
                preset = AgentMediaTranscodePreset.COMPACT,
                startMillis = 1_250,
                durationMillis = 4_500,
                maxWidth = 1_280,
                maxHeight = 720,
                audioBitrateKbps = 128
            ),
            "inputs/my clip.mov",
            "outputs/my clip.mp4"
        )

        assertTrue(plan.arguments.windowed(2).contains(listOf("-ss", "1.25")))
        assertTrue(plan.arguments.windowed(2).contains(listOf("-t", "4.5")))
        assertTrue(plan.arguments.windowed(2).contains(listOf("-b:a", "128k")))
        assertTrue(plan.arguments.single { it.startsWith("scale=") }.contains("min(1280,iw)"))
        assertTrue(plan.arguments.contains("./inputs/my clip.mov"))
        assertTrue(plan.arguments.contains("./outputs/my clip.mp4"))
    }

    @Test
    fun rejectsTraversalSamePathAndMismatchedExtension() {
        val request = request(AgentMediaTargetFormat.MP4)

        assertThrows(IllegalArgumentException::class.java) {
            AgentFfmpegTranscodePlanner.create(request, "../source.mov", "outputs/result.mp4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentFfmpegTranscodePlanner.create(request, "inputs/source.mp4", "inputs/source.mp4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentFfmpegTranscodePlanner.create(request, "inputs/source.mov", "outputs/result.gif")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentFfmpegTranscodePlanner.create(request, "main.ffmpeg.json", "outputs/result.mp4")
        }
    }

    @Test
    fun compactAndBalancedVideoPresetsBoundResolutionWhileHighQualityPreservesIt() {
        val compact = AgentFfmpegTranscodePlanner.create(
            request(AgentMediaTargetFormat.MP4).copy(preset = AgentMediaTranscodePreset.COMPACT),
            "inputs/source.mov",
            "outputs/compact.mp4"
        )
        val balanced = AgentFfmpegTranscodePlanner.create(
            request(AgentMediaTargetFormat.MP4).copy(preset = AgentMediaTranscodePreset.BALANCED),
            "inputs/source.mov",
            "outputs/balanced.mp4"
        )
        val high = AgentFfmpegTranscodePlanner.create(
            request(AgentMediaTargetFormat.MP4).copy(preset = AgentMediaTranscodePreset.HIGH_QUALITY),
            "inputs/source.mov",
            "outputs/high.mp4"
        )

        assertTrue(compact.arguments.single { it.startsWith("scale=") }.contains("min(1280,iw)"))
        assertTrue(balanced.arguments.single { it.startsWith("scale=") }.contains("min(1920,iw)"))
        assertTrue(high.arguments.single { it.startsWith("scale=") }.contains("trunc(iw/2)*2"))
    }

    private fun request(format: AgentMediaTargetFormat) = AgentMediaTranscodeRequest(
        sourcePath = "inputs/source.mov",
        targetFormat = format,
        workspaceId = "workspace-1",
        invocationId = "invocation-1"
    )
}
