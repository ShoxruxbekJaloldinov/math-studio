package com.example.math_studio

import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderServiceBitrateTest {
    @Test
    fun bitrateIncreasesWithHigherResolutionAndFps() {
        val h264Fhd30 = RecorderService.calculateVideoBitrate(1920, 1080, 30, VideoCodec.H264)
        val h264Fhd120 = RecorderService.calculateVideoBitrate(1920, 1080, 120, VideoCodec.H264)
        val hevcFhd30 = RecorderService.calculateVideoBitrate(1920, 1080, 30, VideoCodec.HEVC)

        assertTrue(h264Fhd120 > h264Fhd30)
        assertTrue(hevcFhd30 < h264Fhd30)
        assertTrue(hevcFhd30 >= 4_000_000)
        assertTrue(hevcFhd30 <= 6_000_000)
    }
}
