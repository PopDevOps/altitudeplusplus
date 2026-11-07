package com.altitudeplusplus.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SignInScreen(model: AppViewModel) {
    val state by model.signIn.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text("Altitude++", style = MaterialTheme.typography.displaySmall)
            Text(
                "Sign in with the email on your Altitude+ subscription. Altitude+ will email you a 6-digit code.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.key == null) {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = model::setEmail,
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { model.sendCode() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = model::sendCode, enabled = !state.busy && state.email.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.busy) "Sending…" else "Email me a code")
                }
            } else {
                Text("Code sent to ${state.email}.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("6-digit code") },
                    singleLine = true,
                    enabled = !state.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.verify(code) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { model.verify(code) }, enabled = !state.busy && code.length == 6, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.busy) "Checking…" else "Sign in")
                }
                TextButton(onClick = { code = ""; model.restartSignIn() }) { Text("Use a different email") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Unofficial app. Not affiliated with Altitude Sports & Entertainment or ViewLift. Requires your own subscription inside Altitude's broadcast territory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
