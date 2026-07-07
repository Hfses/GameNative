package app.gamenative.ui.screen.gamehub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gamenative.gamehub.custom.AuthType
import app.gamenative.gamehub.custom.CustomStoreConfig

/**
 * The "add / edit store" form. Every field of a [CustomStoreConfig] is a text input the user fills
 * in to describe a legitimate store's official "my library" API. On save it builds a config; the
 * hub then talks to that store's API — it never imports download-link lists.
 */
@Composable
fun CustomStoreDialog(
    initial: CustomStoreConfig?,
    onSave: (CustomStoreConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var id by rememberSaveable { mutableStateOf(initial?.id ?: "") }
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var iconUrl by rememberSaveable { mutableStateOf(initial?.iconUrl ?: "") }
    var authTypeName by rememberSaveable { mutableStateOf((initial?.authType ?: AuthType.NONE).name) }
    var authHeaderName by rememberSaveable { mutableStateOf(initial?.authHeaderName ?: "Authorization") }
    var authScheme by rememberSaveable { mutableStateOf(initial?.authScheme ?: "Bearer ") }
    var authToken by rememberSaveable { mutableStateOf(initial?.authToken ?: "") }
    var httpMethod by rememberSaveable { mutableStateOf(initial?.httpMethod ?: "GET") }
    var libraryEndpoint by rememberSaveable { mutableStateOf(initial?.libraryEndpoint ?: "") }
    var extraHeaders by rememberSaveable { mutableStateOf(initial?.extraHeaders ?: "") }
    var gamesArrayPath by rememberSaveable { mutableStateOf(initial?.gamesArrayPath ?: "") }
    var fieldId by rememberSaveable { mutableStateOf(initial?.fieldId ?: "id") }
    var fieldName by rememberSaveable { mutableStateOf(initial?.fieldName ?: "name") }
    var fieldCover by rememberSaveable { mutableStateOf(initial?.fieldCover ?: "cover") }
    var fieldDeveloper by rememberSaveable { mutableStateOf(initial?.fieldDeveloper ?: "developer") }
    var fieldInstalled by rememberSaveable { mutableStateOf(initial?.fieldInstalled ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        CustomStoreConfig(
                            id = id.trim(),
                            name = name.trim(),
                            iconUrl = iconUrl.trim(),
                            authType = runCatching { AuthType.valueOf(authTypeName) }.getOrDefault(AuthType.NONE),
                            authHeaderName = authHeaderName.trim(),
                            authScheme = authScheme,
                            authToken = authToken.trim(),
                            httpMethod = httpMethod.trim().ifBlank { "GET" },
                            libraryEndpoint = libraryEndpoint.trim(),
                            extraHeaders = extraHeaders,
                            gamesArrayPath = gamesArrayPath.trim(),
                            fieldId = fieldId.trim().ifBlank { "id" },
                            fieldName = fieldName.trim().ifBlank { "name" },
                            fieldCover = fieldCover.trim(),
                            fieldDeveloper = fieldDeveloper.trim(),
                            fieldInstalled = fieldInstalled.trim(),
                        ),
                    )
                },
                enabled = id.isNotBlank() && name.isNotBlank() && libraryEndpoint.isNotBlank(),
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text(if (initial == null) "Adicionar loja" else "Editar loja") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field("ID (slug único, ex. itchio)", id) { id = it }
                Field("Nome exibido", name) { name = it }
                Field("Ícone (URL, opcional)", iconUrl) { iconUrl = it }

                Text("Autenticação")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AuthType.entries.forEach { type ->
                        FilterChip(
                            selected = authTypeName == type.name,
                            onClick = { authTypeName = type.name },
                            label = { Text(type.name) },
                        )
                    }
                }
                Field("Header de auth (ex. Authorization)", authHeaderName) { authHeaderName = it }
                Field("Prefixo do token (ex. 'Bearer ')", authScheme) { authScheme = it }
                Field("Token / API key", authToken) { authToken = it }

                Text("Requisição da biblioteca")
                Field("Método HTTP (GET/POST)", httpMethod) { httpMethod = it }
                Field("Endpoint da biblioteca (URL)", libraryEndpoint) { libraryEndpoint = it }
                Field("Headers extras (Nome: Valor por linha)", extraHeaders) { extraHeaders = it }

                Text("Como ler a resposta (JSON)")
                Field("Caminho do array de jogos (ex. data.games)", gamesArrayPath) { gamesArrayPath = it }
                Field("Campo do id", fieldId) { fieldId = it }
                Field("Campo do nome", fieldName) { fieldName = it }
                Field("Campo da capa (opcional)", fieldCover) { fieldCover = it }
                Field("Campo do desenvolvedor (opcional)", fieldDeveloper) { fieldDeveloper = it }
                Field("Campo 'instalado' (opcional)", fieldInstalled) { fieldInstalled = it }
            }
        },
    )
}

@Composable
private fun Field(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
