package com.hcwebhook.app.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hcwebhook.app.*
import java.net.MalformedURLException
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebhooksScreen(
    activity: MainActivity
) {
    val context = LocalContext.current
    val preferencesManager = remember { PreferencesManager(context) }
    
    // State
    var webhookConfigs by remember { mutableStateOf(preferencesManager.getWebhookConfigs()) }
    var newUrl by remember { mutableStateOf("") }
    
    // Dialog State
    var showHeaderDialog by remember { mutableStateOf(false) }
    var selectedConfigIndex by remember { mutableStateOf(-1) }

    // Delete confirmation bottom sheet state
    var showDeleteSheet by remember { mutableStateOf(false) }
    var pendingDeleteIndex by remember { mutableStateOf(-1) }

    // Save changes when webhookConfigs changes
    LaunchedEffect(webhookConfigs) {
        preferencesManager.setWebhookConfigs(webhookConfigs)
    }

    val scrollState = rememberScrollState()

    // ── Delete Confirmation Bottom Sheet ──────────────────────────────────────
    if (showDeleteSheet && pendingDeleteIndex in webhookConfigs.indices) {
        val urlToDelete = webhookConfigs[pendingDeleteIndex].url
        ModalBottomSheet(
            onDismissRequest = {
                showDeleteSheet = false
                pendingDeleteIndex = -1
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp)
                )
                Text("Delete Webhook?", style = MaterialTheme.typography.titleLarge)
                Text(
                    "\"$urlToDelete\" will be permanently removed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        webhookConfigs = webhookConfigs.toMutableList().apply { removeAt(pendingDeleteIndex) }
                        showDeleteSheet = false
                        pendingDeleteIndex = -1
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete")
                }
                OutlinedButton(
                    onClick = {
                        showDeleteSheet = false
                        pendingDeleteIndex = -1
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // Webhook URLs Section
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Webhook URLs", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))

                    if (webhookConfigs.isEmpty()) {
                        Text(
                            "No webhooks configured. Add a URL to start syncing data.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Current URLs
                    webhookConfigs.forEachIndexed { index, config ->
                        Column {
                             Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = config.url,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    val headerCount = config.getHeaderCount()
                                    if (headerCount > 0) {
                                        Text(
                                            text = "$headerCount headers configured",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Row {
                                    IconButton(onClick = {
                                        selectedConfigIndex = index
                                        showHeaderDialog = true
                                    }) {
                                        Icon(Icons.Filled.Edit, "Edit Headers")
                                    }
                                    IconButton(onClick = {
                                        pendingDeleteIndex = index
                                        showDeleteSheet = true
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            "Delete",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            Divider()
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Add new URL
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("New Webhook URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val trimmedUrl = newUrl.trim()
                            if (trimmedUrl.isNotBlank() && isValidWebhookUrl(trimmedUrl)) {
                                webhookConfigs = webhookConfigs + WebhookConfig.fromUrl(trimmedUrl)
                                newUrl = ""
                            } else {
                                Toast.makeText(context, "Please enter a valid HTTPS webhook URL", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add Webhook")
                    }
                }
            }
        }
    }
    
    // Header Dialog
    if (showHeaderDialog && selectedConfigIndex in webhookConfigs.indices) {
        val config = webhookConfigs[selectedConfigIndex]
        var currentHeaders by remember { mutableStateOf(config.headers) }
        var newKey by remember { mutableStateOf("") }
        var newValue by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showHeaderDialog = false },
            title = { Text("Manage Headers") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Headers for ${config.url}", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Existing Headers
                    if (currentHeaders.isEmpty()) {
                        Text("No headers added", style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                    
                    currentHeaders.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(key, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(value, style = MaterialTheme.typography.bodySmall) 
                            }
                            IconButton(onClick = {
                                currentHeaders = currentHeaders - key
                            }) {
                                Icon(Icons.Filled.Delete, "Remove Header", modifier = Modifier.size(20.dp))
                            }
                        }
                        Divider()
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Add Header", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it },
                        label = { Text("Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text("Value") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val trimmedKey = newKey.trim()
                            val trimmedValue = newValue.trim()
                            if (trimmedKey.isNotBlank() && trimmedValue.isNotBlank() && isValidHeaderName(trimmedKey)) {
                                currentHeaders = currentHeaders + (trimmedKey to trimmedValue)
                                newKey = ""
                                newValue = ""
                            } else if (trimmedKey.isNotBlank() && !isValidHeaderName(trimmedKey)) {
                                Toast.makeText(context, "Invalid header name. Use only alphanumeric characters, hyphens, and underscores.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add Header")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val updatedConfig = config.copy(headers = currentHeaders)
                    val newList = webhookConfigs.toMutableList()
                    newList[selectedConfigIndex] = updatedConfig
                    webhookConfigs = newList
                    showHeaderDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showHeaderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Validates a webhook URL using proper URL parsing.
 * Requires HTTPS scheme and a valid host with at least a domain.
 */
private fun isValidWebhookUrl(url: String): Boolean {
    return try {
        val parsed = URL(url)
        parsed.protocol == "https" && !parsed.host.isNullOrBlank()
    } catch (e: MalformedURLException) {
        false
    }
}

/**
 * Validates HTTP header names per RFC 7230 (token characters).
 * Allows alphanumeric, hyphens, underscores, and common token chars.
 */
private fun isValidHeaderName(name: String): Boolean {
    if (name.isBlank()) return false
    // RFC 7230 token = 1*tchar
    // tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." /
    //         "^" / "_" / "`" / "|" / "~" / DIGIT / ALPHA
    val tokenPattern = Regex("^[A-Za-z0-9!#\$%&'*+\\-.^_`|~]+$")
    return tokenPattern.matches(name)
}
