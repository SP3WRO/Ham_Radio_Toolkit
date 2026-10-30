package io.github.sp3wro.hamradiotoolkit.utils

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Tekst z zasobów z argumentami – dla ViewModeli i warstwy danych, które nie mają kontekstu w języku ekranu.
 * Ekran zamienia go na napis w swoim języku ([asString]).
 */
class UiText(@StringRes val id: Int, vararg val args: Any) {
    @Composable
    fun asString(): String = stringResource(id, *args)

    fun asString(context: Context): String = context.localizedString(id, *args)
}
