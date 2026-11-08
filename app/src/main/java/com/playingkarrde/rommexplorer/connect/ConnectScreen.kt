package com.playingkarrde.rommexplorer.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playingkarrde.rommexplorer.data.settings.AppSettings
import com.playingkarrde.rommexplorer.ui.AppButton
import com.playingkarrde.rommexplorer.ui.Palette
import com.playingkarrde.rommexplorer.ui.focusRing
import kotlinx.coroutines.launch

data class ConnectForm(val url: String, val username: String, val password: String, val token: String, val allowSelfSigned: Boolean)

@Composable
fun ConnectScreen(
    initial: AppSettings,
    initialError: String?,
    onConnect: suspend (ConnectForm) -> String?,
) {
    var url by rememberSaveable { mutableStateOf(initial.serverUrl) }
    var user by rememberSaveable { mutableStateOf(initial.username) }
    var pass by rememberSaveable { mutableStateOf(initial.password) }
    var token by rememberSaveable { mutableStateOf(initial.token) }
    var selfSigned by rememberSaveable { mutableStateOf(initial.allowSelfSigned) }
    var error by remember { mutableStateOf(initialError) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val first = remember { FocusRequester() }

    fun submit() {
        if (busy) return
        if (url.isBlank()) {
            error = "Enter your RomM server address first."
            return
        }
        busy = true
        error = null
        scope.launch {
            error = onConnect(ConnectForm(url, user, pass, token, selfSigned))
            busy = false
        }
    }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Palette.Background).imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RomM Explorer", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Connect to your RomM server", color = Palette.TextDim)
            Spacer(Modifier.height(4.dp))

            val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
            OutlinedTextField(
                url, { url = it }, label = { Text("Server address") }, placeholder = { Text("http://192.168.1.10:8080") },
                singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(first),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), keyboardActions = next,
            )
            OutlinedTextField(
                user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = next,
            )
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next), keyboardActions = next,
            )
            Text("…or use a RomM API token instead (it takes priority):", color = Palette.Muted, fontSize = 13.sp)
            OutlinedTextField(
                token, { token = it }, label = { Text("API token (rmm_…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); submit() }),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .focusRing(RoundedCornerShape(10.dp))
                    .toggleable(selfSigned, role = Role.Checkbox) { selfSigned = it }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(selfSigned, onCheckedChange = null)
                Text("Allow self-signed HTTPS certificates (this server only)", modifier = Modifier.padding(start = 8.dp))
            }
            error?.let {
                Text(it, color = Palette.Danger, modifier = Modifier.background(Palette.Danger.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(12.dp).fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppButton(if (busy) "Connecting…" else "Connect", ::submit, icon = Icons.Filled.Login, enabled = !busy)
                if (busy) CircularProgressIndicator(Modifier.padding(start = 16.dp).size(24.dp), strokeWidth = 3.dp)
            }
        }
    }
}
