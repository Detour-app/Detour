package com.jellemax.detour.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Covers the config behind [SecretTextField] and [CredentialTextField]. The composables
 * themselves are out of reach here - app/ has plain JUnit4 and no Robolectric, no
 * compose-ui-test and no androidTest source set - so the security decision is extracted
 * into these functions and the shell around them is checked on a device.
 *
 * These types were measured to construct and evaluate on the JVM stub with no Android
 * framework before this test was written; without that, the config would have had to
 * return a framework-free type instead.
 */
class SecureFieldsTest {

    @Test
    fun aSecretFieldAsksForThePasswordKeyboard() {
        // KeyboardType.Password is the whole IME-cache protection: it maps to
        // TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD, which is what stops a
        // compliant IME learning the secret. ASVS 5.0.0 V6.2.6.
        assertEquals(KeyboardType.Password, secretKeyboardOptions().keyboardType)
    }

    @Test
    fun neitherFieldAutocorrects() {
        // Null resolves to true, so a field that says nothing IS autocorrected.
        // This is the one option here that changes EditorInfo.
        assertEquals(false, secretKeyboardOptions().autoCorrectEnabled)
        assertEquals(false, credentialKeyboardOptions(KeyboardType.Ascii).autoCorrectEnabled)
    }

    @Test
    fun neitherFieldRequestsCapitalisation() {
        // A no-op against the platform default, asserted so the intent survives an edit.
        assertEquals(KeyboardCapitalization.None, secretKeyboardOptions().capitalization)
        assertEquals(
            KeyboardCapitalization.None,
            credentialKeyboardOptions(KeyboardType.Uri).capitalization,
        )
    }

    @Test
    fun aCredentialFieldPassesItsKeyboardTypeThrough() {
        assertEquals(KeyboardType.Uri, credentialKeyboardOptions(KeyboardType.Uri).keyboardType)
        assertEquals(KeyboardType.Ascii, credentialKeyboardOptions(KeyboardType.Ascii).keyboardType)
    }

    @Test
    fun aHiddenSecretIsMasked() {
        val masked = secretMask(revealed = false).filter(AnnotatedString("hunter2"))
        assertEquals("•••••••", masked.text.text)
        assertNotEquals("hunter2", masked.text.text)
    }

    @Test
    fun aRevealedSecretShowsItsText() {
        val shown = secretMask(revealed = true).filter(AnnotatedString("hunter2"))
        assertEquals("hunter2", shown.text.text)
    }

    @Test
    fun revealingDoesNotReachTheKeyboard() {
        // The defect this guards: if revealing also swapped the keyboard back to Text,
        // checking your own typing would start feeding the IME cache - #7 reached by a
        // different route. `revealed` is structurally absent from secretKeyboardOptions'
        // signature, and this asserts the masking function is the only thing it feeds.
        assertNotEquals(
            secretMask(revealed = false).filter(AnnotatedString("abc")).text.text,
            secretMask(revealed = true).filter(AnnotatedString("abc")).text.text,
        )
        assertEquals(KeyboardType.Password, secretKeyboardOptions().keyboardType)
    }
}
