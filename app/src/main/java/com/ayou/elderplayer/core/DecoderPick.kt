package com.ayou.elderplayer.core

/**
 * 视频解码器怎么选。老电视 CPU 弱，4K H265 一走软解就卡，所以：
 * - 有硬解就只用硬解（OMX.amlogic.*、OMX.MTK.* 这类），谷歌自带的软解码器去掉；
 * - 一个硬解都没有（比如很老的格式），才留着软解，总比放不了强。
 */
object DecoderPick {

    private val SOFTWARE_PREFIXES = listOf("OMX.google.", "c2.android.", "OMX.ffmpeg.", "c2.ffmpeg.")

    fun isSoftware(name: String, softwareOnly: Boolean = false): Boolean =
        softwareOnly || SOFTWARE_PREFIXES.any { name.startsWith(it, ignoreCase = true) } ||
            name.contains("ffmpeg", ignoreCase = true)

    fun <T> hardwareFirst(decoders: List<T>, isSoftware: (T) -> Boolean): List<T> =
        decoders.filterNot(isSoftware).ifEmpty { decoders }

    /** 维护页上显示：「OMX.amlogic.hevc.decoder.awesome（硬解）」 */
    fun label(name: String?): String =
        if (name == null) "—" else "$name（${if (isSoftware(name)) "软解" else "硬解"}）"
}
