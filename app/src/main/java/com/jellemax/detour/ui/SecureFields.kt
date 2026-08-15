package com.jellemax.detour.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Input fields for credentials, and the config behind them.
 *
 * Compose does not infer any of this from [PasswordVisualTransformation]: that only affects
 * what is drawn. Without [KeyboardOptions] a field ships as ordinary prose, so a typed
 * secret can land in the keyboard's personalised learning dictionary - which several
 * third-party IMEs sync off-device. Masking hides the secret from someone looking over your
 * shoulder; it does not change what the keyboard does with the keystrokes.
 *
 * What each setting is actually worth, measured against Foundation 1.7 and AOSP's
 * `EditorInfo.update()` rather than assumed:
 *
 *  - [KeyboardType.Password] -> `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD`. This is
 *    the entire IME-cache protection.
 *  - `autoCorrectEnabled = false` clears `TYPE_TEXT_FLAG_AUTO_CORRECT`, which a null default
 *    *sets*. Real: these fields are autocorrected today.
 *  - [KeyboardCapitalization.None] does nothing. `Unspecified` already resolves to `None`,
 *    which ORs no flag. Written for intent, not effect.
 *
 * `IME_FLAG_NO_PERSONALIZED_LEARNING` would be the belt-and-braces version and is not
 * reachable: `PlatformImeOptions` carries only `privateImeOptions: String?`, the flag is an
 * Int on `EditorInfo.imeOptions`, and Compose never sets it.
 *
 * Requirements: ASVS 5.0.0 V6.2.6 (mask, with a permitted temporary reveal) and V6.2.7
 * (paste and password managers must work). CWE-549.
 */

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
