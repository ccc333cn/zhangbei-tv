package com.ayou.elderplayer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderPickTest {

    private fun pick(vararg names: String) = DecoderPick.hardwareFirst(names.toList()) { DecoderPick.isSoftware(it) }

    @Test
    fun softwareDecodersAreDroppedWhenHardwareExists() {
        // 小米电视 4 上 HEVC 的实际列表
        assertEquals(listOf("OMX.amlogic.hevc.decoder.awesome"),
            pick("OMX.google.hevc.decoder", "OMX.amlogic.hevc.decoder.awesome"))
        assertEquals(listOf("OMX.MTK.VIDEO.DECODER.HEVC"), pick("c2.android.hevc.decoder", "OMX.MTK.VIDEO.DECODER.HEVC"))
    }

    @Test
    fun keepsSoftwareWhenThereIsNoHardware() {
        assertEquals(listOf("OMX.google.vp9.decoder"), pick("OMX.google.vp9.decoder"))
    }

    @Test
    fun labels() {
        assertEquals("OMX.amlogic.avc.decoder.awesome（硬解）", DecoderPick.label("OMX.amlogic.avc.decoder.awesome"))
        assertEquals("OMX.google.h264.decoder（软解）", DecoderPick.label("OMX.google.h264.decoder"))
        assertEquals("ffmpegLib（软解）", DecoderPick.label("ffmpegLib"))
        assertEquals("—", DecoderPick.label(null))
    }
}
