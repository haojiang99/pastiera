package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val lang: String,
    val emotion: String,
    val event: String,
    val durations: FloatArray,
)

data class OfflineTransducerModelConfig(
    @JvmField var encoder: String = "",
    @JvmField var decoder: String = "",
    @JvmField var joiner: String = "",
)

data class OfflineParaformerModelConfig(
    @JvmField var model: String = "",
)

data class OfflineNemoEncDecCtcModelConfig(
    @JvmField var model: String = "",
)

data class OfflineDolphinModelConfig(
    @JvmField var model: String = "",
)

data class OfflineZipformerCtcModelConfig(
    @JvmField var model: String = "",
    @JvmField var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineWenetCtcModelConfig(
    @JvmField var model: String = "",
)

data class OfflineOmnilingualAsrCtcModelConfig(
    @JvmField var model: String = "",
)

data class OfflineWhisperModelConfig(
    @JvmField var encoder: String = "",
    @JvmField var decoder: String = "",
    @JvmField var language: String = "en",
    @JvmField var task: String = "transcribe",
    @JvmField var tailPaddings: Int = 1000,
)

data class OfflineCanaryModelConfig(
    @JvmField var encoder: String = "",
    @JvmField var decoder: String = "",
    @JvmField var srcLang: String = "en",
    @JvmField var tgtLang: String = "en",
    @JvmField var usePnc: Boolean = true,
)

data class OfflineFireRedAsrModelConfig(
    @JvmField var encoder: String = "",
    @JvmField var decoder: String = "",
)

data class OfflineMoonshineModelConfig(
    @JvmField var preprocessor: String = "",
    @JvmField var encoder: String = "",
    @JvmField var uncachedDecoder: String = "",
    @JvmField var cachedDecoder: String = "",
)

data class OfflineSenseVoiceModelConfig(
    @JvmField var model: String = "",
    @JvmField var language: String = "",
    @JvmField var useInverseTextNormalization: Boolean = true,
    @JvmField var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineModelConfig(
    @JvmField var transducer: OfflineTransducerModelConfig = OfflineTransducerModelConfig(),
    @JvmField var paraformer: OfflineParaformerModelConfig = OfflineParaformerModelConfig(),
    @JvmField var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    @JvmField var fireRedAsr: OfflineFireRedAsrModelConfig = OfflineFireRedAsrModelConfig(),
    @JvmField var moonshine: OfflineMoonshineModelConfig = OfflineMoonshineModelConfig(),
    @JvmField var nemo: OfflineNemoEncDecCtcModelConfig = OfflineNemoEncDecCtcModelConfig(),
    @JvmField var senseVoice: OfflineSenseVoiceModelConfig = OfflineSenseVoiceModelConfig(),
    @JvmField var dolphin: OfflineDolphinModelConfig = OfflineDolphinModelConfig(),
    @JvmField var zipformerCtc: OfflineZipformerCtcModelConfig = OfflineZipformerCtcModelConfig(),
    @JvmField var wenetCtc: OfflineWenetCtcModelConfig = OfflineWenetCtcModelConfig(),
    @JvmField var omnilingual: OfflineOmnilingualAsrCtcModelConfig = OfflineOmnilingualAsrCtcModelConfig(),
    @JvmField var canary: OfflineCanaryModelConfig = OfflineCanaryModelConfig(),
    @JvmField var teleSpeech: String = "",
    @JvmField var numThreads: Int = 1,
    @JvmField var debug: Boolean = false,
    @JvmField var provider: String = "cpu",
    @JvmField var modelType: String = "",
    @JvmField var tokens: String = "",
    @JvmField var modelingUnit: String = "",
    @JvmField var bpeVocab: String = "",
)

data class OfflineRecognizerConfig(
    @JvmField var featConfig: FeatureConfig = FeatureConfig(),
    @JvmField var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    @JvmField var hr: HomophoneReplacerConfig = HomophoneReplacerConfig(),
    @JvmField var decodingMethod: String = "greedy_search",
    @JvmField var maxActivePaths: Int = 4,
    @JvmField var hotwordsFile: String = "",
    @JvmField var hotwordsScore: Float = 1.5f,
    @JvmField var ruleFsts: String = "",
    @JvmField var ruleFars: String = "",
    @JvmField var blankPenalty: Float = 0.0f,
)

class OfflineRecognizer(
    assetManager: AssetManager? = null,
    val config: OfflineRecognizerConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(): OfflineStream {
        val p = createStream(ptr)
        return OfflineStream(p)
    }

    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        val objArray = getResult(stream.ptr)

        val text = objArray[0] as String
        @Suppress("UNCHECKED_CAST")
        val tokens = objArray[1] as Array<String>
        val timestamps = objArray[2] as FloatArray
        val lang = objArray[3] as String
        val emotion = objArray[4] as String
        val event = objArray[5] as String
        val durations = objArray[6] as FloatArray
        return OfflineRecognizerResult(
            text = text,
            tokens = tokens,
            timestamps = timestamps,
            lang = lang,
            emotion = emotion,
            event = event,
            durations = durations,
        )
    }

    fun decode(stream: OfflineStream) = decode(ptr, stream.ptr)

    fun setConfig(config: OfflineRecognizerConfig) = setConfig(ptr, config)

    private external fun delete(ptr: Long)

    private external fun createStream(ptr: Long): Long

    private external fun setConfig(ptr: Long, config: OfflineRecognizerConfig)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineRecognizerConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineRecognizerConfig,
    ): Long

    private external fun decode(ptr: Long, streamPtr: Long)

    private external fun getResult(streamPtr: Long): Array<Any>

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }

        @JvmStatic
        external fun prependAdspLibraryPath(newPath: String)
    }
}
