package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.data.model.DxSpot
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Body
import retrofit2.http.Query
import retrofit2.Response
import io.github.sp3wro.hamradiotoolkit.data.model.PostSpotRequest

interface DxClusterApi {
    @GET("spots")
    suspend fun getSpots(@Query("limit") limit: Int = 50): List<DxSpot>

    @POST("spots")
    suspend fun postSpot(@Body request: PostSpotRequest): Response<Unit>

    companion object {
        private const val BASE_URL = "https://www.dxsummit.fi/api/v1/"
        
        // Ignoring unknown keys, because DX Summit returns many fields we don't need
        // coerceInputValues prevents crashes when a field like 'info' is 'null' in JSON
        private val json = Json { 
            ignoreUnknownKeys = true
            coerceInputValues = true 
        }

        fun create(): DxClusterApi {
            val contentType = "application/json".toMediaType()
            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(HttpClients.default)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()

            return retrofit.create(DxClusterApi::class.java)
        }
    }
}