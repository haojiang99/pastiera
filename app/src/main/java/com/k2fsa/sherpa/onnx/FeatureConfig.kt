package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    @JvmField var sampleRate: Int = 16000,
    @JvmField var featureDim: Int = 80,
    @JvmField var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
