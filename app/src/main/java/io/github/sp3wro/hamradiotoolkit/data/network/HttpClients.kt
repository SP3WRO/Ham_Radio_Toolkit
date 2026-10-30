package io.github.sp3wro.hamradiotoolkit.data.network

import io.github.sp3wro.hamradiotoolkit.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// Jeden współdzielony klient HTTP dla całej aplikacji (wspólna pula połączeń i cache DNS)
object HttpClients {
    const val USER_AGENT = "HamRadioToolkit/${BuildConfig.VERSION_NAME} (Android)"

    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.header("User-Agent") != null) {
                    chain.proceed(request)
                } else {
                    chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
                }
            }
            .build()
    }

    /**
     * Do wyszukiwania znaków: całe zapytanie (łączenie + odpowiedź) najwyżej 10 s, żeby jeden wolny
     * serwis nie wstrzymywał wyniku. Ta sama pula połączeń co [default].
     */
    val lookup: OkHttpClient by lazy {
        default.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
