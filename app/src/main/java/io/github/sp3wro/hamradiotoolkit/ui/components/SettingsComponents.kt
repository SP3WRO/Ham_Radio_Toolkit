package io.github.sp3wro.hamradiotoolkit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.sp3wro.hamradiotoolkit.R

// Wspólne elementy ekranu ustawień: jedna typografia (MaterialTheme.typography) i jeden wzorzec
// wiersza – tytuł + bieżąca wartość pod spodem; edycja w okienku, zapis od razu po zatwierdzeniu.

@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors()
        ) {
            Column(content = content)
        }
    }
}

/** Separator między wierszami w obrębie jednej sekcji. */
@Composable
fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun SettingsItem(
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = summary?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    )
}

@Composable
fun SettingsSwitchItem(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = summary?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
    )
}

/** Wybór jednej opcji z listy: wiersz pokazuje bieżącą wartość, dotknięcie otwiera listę. */
@Composable
fun <T> SettingsChoiceItem(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    summaryOverride: String? = null,
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selected }?.second ?: "—"
    SettingsItem(title = title, summary = summaryOverride ?: currentLabel, onClick = { open = true })
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn {
                    items(options) { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = value == selected, role = Role.RadioButton) {
                                    onSelect(value)
                                    open = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

/** Pole tekstowe edytowane w okienku; zapis po "Zapisz". */
@Composable
fun SettingsTextItem(
    title: String,
    value: String,
    emptySummary: String = stringResource(R.string.settings_not_set),
    placeholder: String = "",
    isSecret: Boolean = false,
    uppercase: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: (String) -> String? = { null }, // komunikat błędu albo null
    onSave: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val summary = when {
        value.isBlank() -> emptySummary
        isSecret -> stringResource(R.string.settings_set)
        else -> value
    }
    SettingsItem(title = title, summary = summary, onClick = { open = true })
    if (open) {
        var input by remember(value) { mutableStateOf(value) }
        val error = validate(input.trim())
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = if (uppercase) it.uppercase() else it },
                    placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    visualTransformation = if (isSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (uppercase) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                        keyboardType = if (isSecret) KeyboardType.Password else keyboardType,
                        autoCorrectEnabled = false
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(input.trim())
                        open = false
                    },
                    enabled = error == null
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

/** Suwak z wartością w tytule; zapis po puszczeniu suwaka. Skok [step] bez znaczników na torze. */
@Composable
fun SettingsSliderItem(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String,
    onSave: (Int) -> Unit
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(format(current.toInt()), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = current,
            onValueChange = { current = (Math.round(it / step) * step).toFloat() },
            onValueChangeFinished = { onSave(current.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat()
        )
    }
}
