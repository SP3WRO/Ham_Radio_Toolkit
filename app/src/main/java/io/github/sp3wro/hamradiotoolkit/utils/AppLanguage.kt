package io.github.sp3wro.hamradiotoolkit.utils

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Język aplikacji wybierany w Ustawieniach. [tag] pusty = język systemu (gdy systemowego nie ma w aplikacji,
 * Android bierze zasoby z res/values, czyli angielskie). Nazwy pokazujemy w ich własnym języku.
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", ""),
    PL("pl", "Polski"),
    EN("en", "English"),
    DE("de", "Deutsch"),
    CS("cs", "Čeština"),
    SK("sk", "Slovenčina"),
    RU("ru", "Русский");

    companion object {
        private const val PREFS = "app_language"
        private const val KEY = "language"

        fun fromTag(tag: String?): AppLanguage = values().firstOrNull { it.tag == tag } ?: SYSTEM

        /**
         * Zapisany wybór. Od Androida 13 źródłem prawdy jest system (język można też zmienić w ustawieniach
         * systemu dla aplikacji), wcześniej – SharedPreferences, czytane synchronicznie przy tworzeniu aktywności.
         */
        fun current(context: Context): AppLanguage {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
                return if (locales == null || locales.isEmpty) SYSTEM else fromTag(locales[0].language)
            }
            return fromTag(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, ""))
        }

        /** Ustawia język i odświeża ekran. */
        fun apply(activity: Activity, language: AppLanguage) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // System sam odtwarza aktywności z nowym językiem
                activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    if (language == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
            } else {
                activity.recreate()
            }
        }

        /**
         * Kontekst w wybranym języku – dla Androida poniżej 13 (MainActivity.attachBaseContext, powiadomienia,
         * widżet). Od Androida 13 system robi to sam dla całego procesu.
         */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val language = current(base)
            if (language == SYSTEM) {
                // Powrót do języka systemu po wcześniejszym wyborze innego (domyślny Locale był nadpisany)
                Locale.setDefault(android.content.res.Resources.getSystem().configuration.locales[0])
                return base
            }
            val locale = Locale.forLanguageTag(language.tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLocales(LocaleList(locale))
            return base.createConfigurationContext(config)
        }
    }
}

/**
 * Tekst z zasobów w języku aplikacji – dla kodu spoza ekranów (ViewModele, powiadomienia, widżet), który ma
 * kontekst aplikacji. Poniżej Androida 13 kontekst aplikacji nie zna wybranego języka, stąd [AppLanguage.wrap].
 */
fun Context.localizedString(@StringRes id: Int, vararg args: Any): String =
    AppLanguage.wrap(this).getString(id, *args)
