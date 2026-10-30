package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.data.model.TropocastMetadata
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query

interface TropocastApi {
    @GET("api/v1/tropo/metadata")
    suspend fun getMetadata(
        @Query("model") model: String = "full",
        @Query("area") area: String = "poland",
        @Query("source") source: String = "icon-eu"
    ): TropocastMetadata

    companion object {
        private const val BASE_URL = "https://tropocast.eu/"
        
        private val json = Json { 
            ignoreUnknownKeys = true 
            coerceInputValues = true
        }

        fun create(): TropocastApi {
            val contentType = "application/json".toMediaType()
            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(HttpClients.default)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()

            return retrofit.create(TropocastApi::class.java)
        }
    }
}