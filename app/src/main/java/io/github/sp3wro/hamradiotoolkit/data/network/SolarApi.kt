package io.github.sp3wro.hamradiotoolkit.data.network

import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET

interface SolarApi {
    @GET("solarxml.php")
    suspend fun getSolarDataRaw(): String

    companion object {
        private const val BASE_URL = "https://www.hamqsl.com/"

        fun create(): SolarApi {
            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(HttpClients.default)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build()

            return retrofit.create(SolarApi::class.java)
        }
    }
}