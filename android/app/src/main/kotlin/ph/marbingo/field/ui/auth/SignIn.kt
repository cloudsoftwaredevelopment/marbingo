package ph.marbingo.field.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.ui.update.UpdateBannerHost

data class SignInState(
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit get() = email.isNotBlank() && password.isNotEmpty() && !loading
}

class SignInViewModel(private val deps: UiDeps) : ViewModel() {
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    fun setEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    /** On success the session flow flips and FieldApp navigates to Home. */
    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                deps.auth.signIn(s.email.trim(), s.password)
                _state.update { it.copy(loading = false, password = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Sign-in failed. Check your connection and try again.") }
            }
        }
    }
}

@Composable
fun SignInScreen(vm: SignInViewModel) {
    val s by vm.state.collectAsState()
    val focus = LocalFocusManager.current
    var show by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Mb.Ground).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            UpdateBannerHost()
            BrandLogo(72.dp)
            Text("Marbingo Field", style = MaterialTheme.typography.headlineMedium)
            Text("Sign in to take orders at your stores.", style = MaterialTheme.typography.bodyMedium, color = Mb.Muted)
            Spacer(Modifier.height(4.dp))
            MbField(
                s.email, vm::setEmail, "Email",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            )
            MbField(
                s.password, vm::setPassword, "Password",
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); vm.submit() }),
                trailingIcon = {
                    IconButton(onClick = { show = !show }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (show) "Hide password" else "Show password",
                        )
                    }
                },
            )
            s.error?.let { ErrorBanner(it) }
            PrimaryButton("Sign in", vm::submit, Modifier.fillMaxWidth(), enabled = s.canSubmit, loading = s.loading)
            Text(
                "First sign-in needs an internet connection.",
                style = MaterialTheme.typography.bodySmall,
                color = Mb.Muted,
            )
        }
    }
}
