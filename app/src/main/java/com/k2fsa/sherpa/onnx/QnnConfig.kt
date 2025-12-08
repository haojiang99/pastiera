package com.k2fsa.sherpa.onnx

data class QnnConfig(
    @JvmField var backendLib: String = "",
    @JvmField var contextBinary: String = "",
    @JvmField var systemLib: String = "",
)
