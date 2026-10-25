package ph.marbingo.field.ui.stores

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ph.marbingo.field.data.Store
import ph.marbingo.field.data.StoreRepository
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.theme.Mb

data class StoreDraft(
    val name: String = "",
    val owner: String = "",
    val phone: String = "",
    val address: String = "",
    val notes: String = "",
) {
    val valid get() = name.isNotBlank()
}

/** Shared by the Stores screen and the New order flow. */
class StoreCreator(private val repo: StoreRepository, private val scope: CoroutineScope) {
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    fun create(d: StoreDraft, onCreated: (String) -> Unit) {
        if (!d.valid || _saving.value) return
        _saving.value = true
        _error.value = null
        scope.launch {
            try {
                val id = repo.create(
                    d.name.trim(),
                    d.owner.trim().ifEmpty { null },
                    d.phone.trim().ifEmpty { null },
                    d.address.trim().ifEmpty { null },
                    d.notes.trim().ifEmpty { null },
                )
                _saving.value = false
                onCreated(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _saving.value = false
                _error.value = e.message ?: "Could not save the store."
            }
        }
    }
}

@Composable
fun AddStoreDialog(
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (StoreDraft) -> Unit,
    initialName: String = "",
) {
    var draft by remember { mutableStateOf(StoreDraft(name = initialName)) }
    var triedSave by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = Mb.Surface,
        title = { Text("Add store", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MbField(
                    draft.name, { draft = draft.copy(name = it) }, "Store name *",
                    isError = triedSave && !draft.valid,
                    supportingText = if (triedSave && !draft.valid) "Name is required" else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                MbField(
                    draft.owner, { draft = draft.copy(owner = it) }, "Owner",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                MbField(
                    draft.phone, { draft = draft.copy(phone = it) }, "Phone",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                )
                MbField(
                    draft.address, { draft = draft.copy(address = it) }, "Address",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                )
                MbField(
                    draft.notes, { draft = draft.copy(notes = it) }, "Notes",
                    singleLine = false, minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            PrimaryButton(
                "Save store",
                onClick = { triedSave = true; if (draft.valid) onSave(draft) },
                loading = saving,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

@Composable
fun StoreRow(s: Store, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ph.marbingo.field.ui.theme.MbShapes.Card,
        color = Mb.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Mb.Line),
    ) {
        Row(Modifier.padding(14.dp).heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium)
                val sub = listOfNotNull(s.ownerName, s.address).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
            }
            if (s.pendingSync) { Spacer(Modifier.width(8.dp)); Chip("Not synced yet", Tone.Warn) }
            trailing?.invoke()
        }
    }
}
