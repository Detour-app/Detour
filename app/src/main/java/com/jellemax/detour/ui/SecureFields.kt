package com.jellemax.detour.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

// Input fields for credentials, and the config behind them.
//
// Compose does not infer any of this from [PasswordVisualTransformation]: that only affects
// what is drawn. Without [KeyboardOptions] a field ships as ordinary prose, so a typed
// secret can land in the keyboard's personalised learning dictionary - which several
// third-party IMEs sync off-device. Masking hides the secret from someone looking over your
// shoulder; it does not change what the keyboard does with the keystrokes.
//
// What each setting is actually worth, measured against Foundation 1.7 and AOSP's
// `EditorInfo.update()` rather than assumed:
//
//  - [KeyboardType.Password] -> `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD`. This is
//    the entire IME-cache protection.
//  - `autoCorrectEnabled = false` clears `TYPE_TEXT_FLAG_AUTO_CORRECT`, which a null default
//    *sets*. Real: these fields are autocorrected today.
//  - [KeyboardCapitalization.None] does nothing. `Unspecified` already resolves to `None`,
//    which ORs no flag. Written for intent, not effect.
//
// `IME_FLAG_NO_PERSONALIZED_LEARNING` would be the belt-and-braces version and is not
// reachable: `PlatformImeOptions` carries only `privateImeOptions: String?`, the flag is an
// Int on `EditorInfo.imeOptions`, and Compose never sets it.
//
// Requirements: ASVS 5.0.0 V6.2.6 (mask, with a permitted temporary reveal) and V6.2.7
// (paste and password managers must work). CWE-549.

/**
 * Keyboard config for any credential: no autocorrect, no capitalisation, and a caller-chosen
 * [keyboardType]. Not masked - use [secretKeyboardOptions] for something that is secret.
 */
internal fun credentialKeyboardOptions(keyboardType: KeyboardType) = KeyboardOptions(
    keyboardType = keyboardType,
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
)

/** Keyboard config for a secret: [credentialKeyboardOptions] plus the password IME. */
internal fun secretKeyboardOptions() = credentialKeyboardOptions(KeyboardType.Password)

/**
 * The mask, and the only thing a reveal toggle is allowed to change. Deliberately not a
 * parameter of [secretKeyboardOptions]: a revealed secret must still refuse the IME's
 * learning dictionary.
 */
internal fun secretMask(revealed: Boolean): VisualTransformation =
    if (revealed) VisualTransformation.None else PasswordVisualTransformation()

/**
 * Offers this field to the platform autofill service, so a password manager can fill it.
 *
 * ASVS 5.0.0 V6.2.7 (L1) requires that "paste functionality, browser password helpers, and
 * external password managers are permitted", which makes this a requirement rather than a
 * convenience. It also cuts the other way from the usual instinct: suppressing autofill on a
 * credential field is exactly what breaks password managers, so the HTML5 cheat sheet's
 * `autocomplete="off"` advice is deliberately *not* followed here.
 *
 * This is the pre-1.8 autofill API. `Modifier.semantics { contentType = ContentType.Password }`
 * would be tidier but arrived in Compose 1.8; this project is on BOM 2024.09.02.
 * [LocalAutofill] is null on platforms without a service, hence the safe call.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.autofillPassword(onFill: (String) -> Unit): Modifier {
    val autofill = LocalAutofill.current
    // The node is remembered, so without this the first onFill would be captured forever and
    // an autofilled value would be written through a stale lambda. See
    // .claude/skills/detour-compose-state-hazards/ section 2.
    val currentOnFill by rememberUpdatedState(onFill)
    val node = remember { AutofillNode(autofillTypes = listOf(AutofillType.Password)) { currentOnFill(it) } }
    LocalAutofillTree.current += node
    return this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { state ->
            autofill?.run {
                if (state.isFocused) requestAutofillForNode(node) else cancelAutofillForNode(node)
            }
        }
}

/**
 * A masked field for something secret, with a reveal toggle that re-hides itself.
 *
 * There is deliberately no `visualTransformation` parameter: no argument you can pass this
 * composable produces an unmasked field. That is the whole reason it exists rather than a
 * pair of helper functions - #7 happened because one line was omitted from an
 * `OutlinedTextField`, and an omission is exactly what a helper cannot prevent.
 *
 * The reveal is sanctioned rather than tolerated: ASVS 5.0.0 V6.2.6 says applications "may
 * allow the user to temporarily view the entire masked password". It re-hides on focus loss
 * so a revealed secret cannot survive into a screenshot, a recent-apps thumbnail, or the
 * next visit to the screen.
 */
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = secretMask(revealed),
        keyboardOptions = secretKeyboardOptions(),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide secret" else "Show secret",
                )
            }
        },
        // hasFocus, NOT isFocused. The reveal button is an IconButton inside this field's
        // own focus subtree, so tapping it takes focus off the text field itself; keyed on
        // isFocused the secret would re-hide on the very tap meant to show it and the
        // toggle would look broken. This compiles clean either way - only a device shows it.
        modifier = modifier
            .autofillPassword(onValueChange)
            .onFocusChanged { if (!it.hasFocus) revealed = false },
    )
}

/**
 * A field for a credential that is not secret - a client id, a server URL.
 *
 * Not masked, because these are not secrets and hiding them only makes them hard to check.
 * What it does carry is the input-hint half: no autocorrect, no capitalisation, and a
 * caller-chosen [keyboardType]. iOS already sets all three on its counterparts
 * (`iosApp/Detour/SettingsScreen.swift:125-131`); Android set none of them.
 */
@Composable
fun CredentialTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { text -> { Text(text) } },
        singleLine = true,
        keyboardOptions = credentialKeyboardOptions(keyboardType),
        modifier = modifier,
    )
}
