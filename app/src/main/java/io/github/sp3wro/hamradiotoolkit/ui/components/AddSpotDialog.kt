package io.github.sp3wro.hamradiotoolkit.ui.components

import io.github.sp3wro.hamradiotoolkit.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun AddSpotDialog(
    onDismissRequest: () -> Unit,
    onSpotSubmit: (dxCall: String, frequency: String, info: String) -> Unit
) {
    var dxCall by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.addspot_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = dxCall,
                    onValueChange = { dxCall = it.uppercase() },
                    label = { Text(stringResource(R.string.addspot_dx_call)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = frequency,
                    onValueChange = { frequency = it },
                    label = { Text(stringResource(R.string.addspot_freq)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = info,
                    onValueChange = { info = it },
                    label = { Text(stringResource(R.string.addspot_comment)) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSpotSubmit(dxCall, frequency, info) },
                enabled = dxCall.isNotBlank() && frequency.isNotBlank()
            ) {
                Text(stringResource(R.string.dx_spot_it))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}