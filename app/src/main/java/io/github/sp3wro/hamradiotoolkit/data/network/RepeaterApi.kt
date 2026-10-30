package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.data.model.RepeaterResponse
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.http.GET

interface RepeaterApi {
    @GET("api/export/json")
    suspend fun getRepeaters(): List<RepeaterResponse>

    companion object {
        private const val BASE_URL = "https://przemienniki.eu/"

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun create(): RepeaterApi {
            val contentType = "application/json".toMediaType()
            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(HttpClients.default)
                .addConverterFactory(json.asConverterFactory(contentType))
                .build()

            return retrofit.create(RepeaterApi::class.java)
        }
    }
}