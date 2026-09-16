package cz.openradio.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AddStationDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, streamUrl: String, logoUrl: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("") }
    var logoUrl by remember { mutableStateOf("") }

    val valid = name.isNotBlank() &&
        (streamUrl.startsWith("http://") || streamUrl.startsWith("https://"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Přidat rádio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Název rádia") },
                    placeholder = { Text("Moje rádio") }
                )
                OutlinedTextField(
                    value = streamUrl,
                    onValueChange = { streamUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("URL streamu") },
                    placeholder = { Text("https://...") }
                )
                OutlinedTextField(
                    value = logoUrl,
                    onValueChange = { logoUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("URL loga (volitelné)") },
                    placeholder = { Text("https://...") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.trim(),
                        streamUrl.trim(),
                        logoUrl.trim().takeIf { it.isNotBlank() }
                    )
                },
                enabled = valid
            ) {
                Text("Uložit")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Zrušit")
            }
        }
    )
}
