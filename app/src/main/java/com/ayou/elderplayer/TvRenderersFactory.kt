package com.ayou.elderplayer

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.ayou.elderplayer.core.DecoderPick
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer

/**
 * 视频只走电视的硬件解码，ffmpeg 只用来解电视解不了的音轨（AC3、DTS 等）。
 * 以前视频也带 ffmpeg 软解兜底，TCL 这类老电视会因为「解码能力不够」的误报选中软解，
 * 4K H265 软解 CPU 跑满，播放一卡一卡的。
 */
@OptIn(UnstableApi::class)
class TvRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true) // 只对音频生效，视频在下面关掉
        setMediaCodecSelector(HARDWARE_VIDEO_FIRST)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // 不加任何扩展（软解）视频渲染器；硬解初始化失败也不悄悄换别的解码器，直接报错交给上层处理
        super.buildVideoRenderers(
            context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, false,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out,
        )
        // 排在电视自带解码器后面：电视能解的音轨还是电视解
        if (extensionRendererMode != EXTENSION_RENDERER_MODE_OFF) {
            out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        }
    }

    private companion object {
        val HARDWARE_VIDEO_FIRST = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            if (!MimeTypes.isVideo(mimeType)) all
            else DecoderPick.hardwareFirst(all) { DecoderPick.isSoftware(it.name, it.softwareOnly) }
        }
    }
}
