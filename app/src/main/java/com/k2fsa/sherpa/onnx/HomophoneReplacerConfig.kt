package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    @JvmField var dictDir: String = "",
    @JvmField var lexicon: String = "",
    @JvmField var ruleFsts: String = "",
)
