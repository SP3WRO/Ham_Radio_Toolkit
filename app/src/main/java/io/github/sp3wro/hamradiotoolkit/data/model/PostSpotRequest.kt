package io.github.sp3wro.hamradiotoolkit.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PostSpotRequest(
    @SerialName("dx_call")
    val dxCall: String,
    
    @SerialName("de_call")
    val deCall: String,
    
    @SerialName("frequency")
    val frequency: Double,
    
    @SerialName("info")
    val info: String = ""
)