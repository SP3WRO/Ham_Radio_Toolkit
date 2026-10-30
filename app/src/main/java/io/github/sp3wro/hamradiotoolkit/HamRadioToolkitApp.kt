package io.github.sp3wro.hamradiotoolkit

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.sp3wro.hamradiotoolkit.utils.AppLanguage
import io.github.sp3wro.hamradiotoolkit.utils.localizedString

class HamRadioToolkitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStrings.init(this)
    }
}

/**
 * Teksty z zasobów w języku aplikacji dla warstwy danych (komunikaty błędów, notatki wyszukiwarki znaków,
 * powiadomienia), która nie ma pod ręką kontekstu ekranu.
 */
object AppStrings {
    // Kontekst aplikacji (nie aktywności) – żyje tyle co proces, więc nie ma wycieku
    @SuppressLint("StaticFieldLeak")
    @Volatile private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    // Bez kontekstu (testy jednostkowe JVM) – identyfikator zasobu zamiast tekstu
    fun get(@StringRes id: Int, vararg args: Any): String =
        app?.localizedString(id, *args) ?: "#$id"

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        app?.let { AppLanguage.wrap(it).resources.getQuantityString(id, count, *args) } ?: "#$id"
}
