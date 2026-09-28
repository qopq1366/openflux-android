package org.openflux.app.ui.profiles

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.openflux.app.LocalOpenFluxApp
import org.openflux.app.OpenFluxApplication
import org.openflux.app.R
import org.openflux.app.data.CookiePushClient
import org.openflux.app.data.CookiePushResult
import org.openflux.app.data.ManualTransport
import org.openflux.app.data.Profile
import org.openflux.app.data.ProfileMode
import org.openflux.app.data.ProfileRepository
import org.openflux.app.data.PushFailure
import org.openflux.app.ui.IntTextField
import org.openflux.app.ui.LongTextField
import org.openflux.app.ui.home.CaptchaWebViewDialog

class ProfileEditViewModel(private val repository: ProfileRepository) : ViewModel() {
    private val _cookiePushMessage = MutableStateFlow<String?>(null)
    val cookiePushMessage: StateFlow<String?> = _cookiePushMessage

    private val _cookiePushBusy = MutableStateFlow(false)
    val cookiePushBusy: StateFlow<Boolean> = _cookiePushBusy

    fun clearCookiePushMessage() {
        _cookiePushMessage.value = null
    }

    fun loadOrNew(id: String?, onLoaded: (Profile) -> Unit) {
        viewModelScope.launch {
            val profile = id?.let { repository.getById(it) } ?: Profile(
                id = "",
                name = "",
                mode = ProfileMode.KEY,
            )
            onLoaded(profile)
        }
    }

    fun save(profile: Profile, onSaved: () -> Unit) {
        viewModelScope.launch {
            repository.save(profile)
            onSaved()
        }
    }

    fun pushCookies(profile: Profile, cookies: String, app: OpenFluxApplication) {
        if (_cookiePushBusy.value) return
        _cookiePushBusy.value = true
        _cookiePushMessage.value = null
        viewModelScope.launch {
            val result = CookiePushClient(profile.controlUrl, profile.keyToken).push(cookies)
            _cookiePushMessage.value = when (result) {
                is CookiePushResult.Sent -> app.getString(R.string.profile_edit_send_cookies_ok)
                is CookiePushResult.Failed -> app.getString(
                    when (result.reason) {
                        PushFailure.NO_CONTROL_URL -> R.string.profile_edit_send_cookies_no_url
                        PushFailure.NO_KEY_TOKEN -> R.string.profile_edit_send_cookies_no_token
                        PushFailure.BAD_CONTROL_URL -> R.string.profile_edit_send_cookies_no_url
                        PushFailure.REJECTED -> R.string.profile_edit_send_cookies_rejected
                        PushFailure.NETWORK -> R.string.profile_edit_send_cookies_network
                    },
                )
            }
            _cookiePushBusy.value = false
        }
    }
}

// The jar is pushed for the key the profile authenticates as, so both a control URL and a key
// token have to be present, and the provider has to be one that can be checked in a browser.
private fun cookiePushAvailable(profile: Profile): Boolean =
    profile.controlUrl.isNotBlank() &&
        profile.keyToken.isNotBlank() &&
        profile.manualTransport in setOf(
            ManualTransport.YANDEX,
            ManualTransport.VOLGA,
            ManualTransport.BOARDS,
            ManualTransport.YANDEX_MULTISTREAM,
        )

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(profileId: String?, importedProfile: Profile? = null, onDone: () -> Unit) {
    val app = LocalOpenFluxApp.current
    val viewModel: ProfileEditViewModel = viewModel(
        factory = viewModelFactory { initializer { ProfileEditViewModel(app.profileRepository) } },
    )
    val defaultMtu by app.settingsRepository.defaultMtu.collectAsState(initial = 1400)
    val defaultDns by app.settingsRepository.defaultDns.collectAsState(initial = "77.88.8.8")

    var profile by remember { mutableStateOf<Profile?>(null) }
    var cookieSheetFor by remember { mutableStateOf<Profile?>(null) }
    val cookiePushMessage by viewModel.cookiePushMessage.collectAsState()
    val cookiePushBusy by viewModel.cookiePushBusy.collectAsState()

    // Keyed on profileId + importedProfile so this only reloads on navigation, not every recomposition.
    LaunchedEffect(profileId, importedProfile) {
        when {
            importedProfile != null -> profile = importedProfile
            profileId != null -> viewModel.loadOrNew(profileId) { profile = it }
            else -> viewModel.loadOrNew(null) { profile = it.copy(mtu = defaultMtu, dnsUpstream = defaultDns) }
        }
    }

    val current = profile ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (profileId == null) R.string.profile_edit_new_title else R.string.profile_edit_title,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = current.name,
                onValueChange = { profile = current.copy(name = it) },
                label = { Text(stringResource(R.string.profile_edit_name)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.profile_edit_mode), modifier = Modifier.padding(top = 16.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = current.mode == ProfileMode.KEY,
                    onClick = { profile = current.copy(mode = ProfileMode.KEY) },
                    label = { Text(stringResource(R.string.profile_edit_mode_key)) },
                )
                FilterChip(
                    selected = current.mode == ProfileMode.MANUAL,
                    onClick = { profile = current.copy(mode = ProfileMode.MANUAL) },
                    label = { Text(stringResource(R.string.profile_edit_mode_manual)) },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            when (current.mode) {
                ProfileMode.KEY -> {
                    OutlinedTextField(
                        value = current.keyToken,
                        onValueChange = { profile = current.copy(keyToken = it) },
                        label = { Text(stringResource(R.string.profile_edit_key_token)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp)) {
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.YANDEX,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.YANDEX) },
                            label = { Text(stringResource(R.string.profile_edit_transport_yandex)) },
                        )
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.YANDEX_MULTISTREAM,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.YANDEX_MULTISTREAM) },
                            label = { Text(stringResource(R.string.profile_edit_transport_multistream)) },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.MAILRU,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.MAILRU) },
                            label = { Text(stringResource(R.string.profile_edit_transport_mailru)) },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.BOARDS,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.BOARDS) },
                            label = { Text(stringResource(R.string.profile_edit_transport_boards)) },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.MTS,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.MTS) },
                            label = { Text(stringResource(R.string.profile_edit_transport_mts)) },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (current.manualTransport == ManualTransport.YANDEX_MULTISTREAM) {
                        OutlinedTextField(
                            value = current.docUrls.joinToString("\n"),
                            onValueChange = { profile = current.copy(docUrls = it.split("\n")) },
                            label = { Text(stringResource(R.string.profile_edit_doc_urls)) },
                            supportingText = { Text(stringResource(R.string.profile_edit_doc_urls_hint)) },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                    } else {
                        OutlinedTextField(
                            value = current.docUrl,
                            onValueChange = { profile = current.copy(docUrl = it) },
                            label = { Text(stringResource(R.string.profile_edit_doc_url)) },
                            supportingText = { Text(stringResource(R.string.profile_edit_doc_url_key_hint)) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                    }
                    if (current.keyToken.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.profile_edit_e2e_encryption),
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                            )
                            Switch(
                                checked = current.e2eEncryption,
                                onCheckedChange = { profile = current.copy(e2eEncryption = it) },
                            )
                        }
                        Text(
                            stringResource(R.string.profile_edit_e2e_encryption_hint),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ProfileMode.MANUAL -> {
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp)) {
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.YANDEX,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.YANDEX) },
                            label = { Text(stringResource(R.string.profile_edit_transport_yandex)) },
                        )
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.VOLGA,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.VOLGA) },
                            label = { Text(stringResource(R.string.profile_edit_transport_volga)) },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.MAX,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.MAX) },
                            label = { Text(stringResource(R.string.profile_edit_transport_max)) },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.MAILRU,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.MAILRU) },
                            label = { Text(stringResource(R.string.profile_edit_transport_mailru)) },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.BOARDS,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.BOARDS) },
                            label = { Text(stringResource(R.string.profile_edit_transport_boards)) },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        FilterChip(
                            selected = current.manualTransport == ManualTransport.MTS,
                            onClick = { profile = current.copy(manualTransport = ManualTransport.MTS) },
                            label = { Text(stringResource(R.string.profile_edit_transport_mts)) },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    when (current.manualTransport) {
                        ManualTransport.YANDEX, ManualTransport.VOLGA, ManualTransport.MAILRU, ManualTransport.BOARDS, ManualTransport.MTS -> {
                            OutlinedTextField(
                                value = current.docUrl,
                                onValueChange = { profile = current.copy(docUrl = it) },
                                label = { Text(stringResource(R.string.profile_edit_doc_url)) },
                                supportingText = if (current.manualTransport == ManualTransport.YANDEX) {
                                    { Text(stringResource(R.string.profile_edit_doc_url_yandex_hint)) }
                                } else {
                                    null
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                        }
                        ManualTransport.MAX -> {
                            OutlinedTextField(
                                value = current.maxToken,
                                onValueChange = { profile = current.copy(maxToken = it) },
                                label = { Text(stringResource(R.string.profile_edit_max_token)) },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                            LongTextField(
                                value = current.maxUid,
                                onValueChange = { profile = current.copy(maxUid = it) },
                                label = { Text(stringResource(R.string.profile_edit_max_uid)) },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                        }
                        ManualTransport.YANDEX_MULTISTREAM -> {
                            // Unreachable from this screen's chips now, but an older/imported profile can still carry it.
                            OutlinedTextField(
                                value = current.docUrls.joinToString("\n"),
                                onValueChange = { profile = current.copy(docUrls = it.split("\n")) },
                                label = { Text(stringResource(R.string.profile_edit_doc_urls)) },
                                supportingText = { Text(stringResource(R.string.profile_edit_doc_urls_hint)) },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                        }
                    }
                }
            }

            Text(stringResource(R.string.profile_edit_advanced), modifier = Modifier.padding(top = 24.dp))
            IntTextField(
                value = current.mtu,
                onValueChange = { profile = current.copy(mtu = it) },
                label = { Text(stringResource(R.string.profile_edit_mtu)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = current.dnsUpstream,
                onValueChange = { profile = current.copy(dnsUpstream = it) },
                label = { Text(stringResource(R.string.profile_edit_dns)) },
                supportingText = { Text(stringResource(R.string.profile_edit_dns_hint)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = current.forceBootstrapDns,
                onValueChange = { profile = current.copy(forceBootstrapDns = it) },
                label = { Text(stringResource(R.string.profile_edit_force_bootstrap_dns)) },
                placeholder = { Text(stringResource(R.string.profile_edit_force_bootstrap_dns_placeholder)) },
                supportingText = { Text(stringResource(R.string.profile_edit_force_bootstrap_dns_hint)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.profile_edit_auto_reconnect),
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(
                    checked = current.autoReconnect,
                    onCheckedChange = { profile = current.copy(autoReconnect = it) },
                )
            }

            if (cookiePushAvailable(current)) {
                OutlinedButton(
                    onClick = { cookieSheetFor = current },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text(stringResource(R.string.profile_edit_send_cookies)) }
                Text(
                    stringResource(R.string.profile_edit_send_cookies_hint),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(modifier = Modifier.padding(top = 24.dp)) {
                Button(onClick = { viewModel.save(current, onDone) }) {
                    Text(stringResource(R.string.profile_edit_save))
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.padding(start = 12.dp)) {
                    Text(stringResource(R.string.profile_edit_cancel))
                }
            }

            cookiePushMessage?.let {
                Text(
                    it,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    cookieSheetFor?.let { target ->
        CaptchaWebViewDialog(
            docUrl = target.docUrl.ifBlank { "https://disk.yandex.ru/" },
            onDismiss = {
                cookieSheetFor = null
                viewModel.clearCookiePushMessage()
            },
            onSolved = { cookies ->
                viewModel.pushCookies(target, cookies, app)
                cookieSheetFor = null
            },
        )
    }
}
