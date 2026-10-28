package com.hcjeong.forestix.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.SpeciesRecord
import com.hcjeong.forestix.common.SpeciesRegistration
import com.hcjeong.forestix.ui.shareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun SpeciesSettingsSheet(onDismiss: () -> Unit, onSelect: ((SpeciesRecord) -> Unit)? = null) {
    val catalog = LocalAppEnvironment.current.speciesCatalog
    val state by catalog.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }; var scientific by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<SpeciesRecord?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                require(output.size() + n <= 8 * 1024 * 1024) { SpeciesRegistration.INVALID_ARCHIVE }
                                output.write(buffer, 0, n)
                            }
                            output.toByteArray()
                        } ?: error("Unable to open the species catalogue.")
                    }
                    val count = catalog.restore(bytes)
                    message = "Imported $count species. Existing entries were preserved."
                } catch (e: Exception) { message = e.message ?: "Unable to import species." }
                finally { busy = false }
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Species", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { name = ""; scientific = ""; adding = true }, enabled = !busy, modifier = Modifier.testTag("species.add")) { Text("Add") }
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
                }
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search species") },
                    placeholder = { Text("Common name, scientific name, or code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val data = catalog.archiveData()
                                val file = withContext(Dispatchers.IO) {
                                    val directory = File(context.cacheDir, "Exports/Species").apply { mkdirs() }
                                    File(directory, "forestix-species-${UUID.randomUUID()}.json").also { it.writeBytes(data) }
                                }
                                shareFile(context, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), "application/json")
                            } catch (e: Exception) { message = e.message ?: "Unable to export species." }
                            finally { busy = false }
                        }
                    }) { Text("Export registered") }
                    TextButton(enabled = !busy, onClick = { importer.launch(arrayOf("application/json", "application/octet-stream")) }) { Text("Import registered") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.loadError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { scope.launch { catalog.refresh() } }) { Text("Retry") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(state.records.filter { it.matches(query) }, key = { it.code }) { s ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            if (onSelect != null) { onSelect(s); onDismiss() } else detail = s
                        }.padding(vertical = 12.dp)) {
                            Text(s.commonName, style = MaterialTheme.typography.bodyLarge)
                            if (s.scientificName.isNotEmpty()) Text(s.scientificName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        if (adding) AlertDialog(onDismissRequest = { if (!busy) adding = false }, title = { Text("Add species") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Common name") }, singleLine = true, modifier = Modifier.testTag("species.commonName"))
                OutlinedTextField(scientific, { scientific = it }, label = { Text("Scientific name (optional)") }, singleLine = true, modifier = Modifier.testTag("species.scientificName"))
                Text("Volume is unavailable until a suitable equation is configured.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), modifier = Modifier.testTag("species.save"), onClick = {
                busy = true
                scope.launch {
                    try {
                        val saved = catalog.add(name, scientific); adding = false
                        if (onSelect != null) { onSelect(saved); onDismiss() }
                    } catch (e: Exception) { message = e.message ?: "Unable to save species." }
                    finally { busy = false }
                }
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { adding = false }, enabled = !busy) { Text("Cancel") } })
        detail?.let { s -> AlertDialog(onDismissRequest = { detail = null }, title = { Text(s.commonName) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.scientificName.isNotEmpty()) Text(s.scientificName)
                Text("Species code: ${s.code}")
                if (s.code.startsWith("USR-")) Text("Volume equation: Not configured")
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("OK") } }) }
        message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("Species") },
            text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
    }
}
