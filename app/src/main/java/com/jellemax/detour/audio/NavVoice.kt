package com.jellemax.detour.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

private const val UTTERANCE_ID = "detour-nav"
private const val TAG = "DetourNavVoice"

/**
 * Spoken turn instructions, for any surface that navigates.
 *
 * Lived in `car/` until convergence 3, which is where the only voice in the app
 * used to be. It never depended on a single `androidx.car` type, and the phone
 * needs exactly the same audio bargain, so it moved here beside [PushToTalk] —
 * the app's other audio client — rather than being written a second time.
 *
 * Android Auto has no voice API of its own: a projected app speaks through the
 * phone's audio stack, and the head unit routes it by *usage*. Hence
 * [AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE] — that is what makes
 * the car duck the radio and play this over the cabin speakers instead of the
 * phone's earpiece, and what keeps a guidance prompt from being treated as
 * media.
 *
 * Focus is taken per prompt ([AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK])
 * and given back when the utterance finishes, so music keeps playing quietly
 * underneath rather than stopping for the whole drive.
 */
class NavVoice(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var holdingFocus = false

    // The engine takes a moment to initialise and the first instruction is
    // usually spoken within that window ("in 300 metres, turn right" fires on
    // the first fix after Start). Held rather than dropped so the first prompt
    // isn't the one that goes missing.
    private var pending: String? = null

    init {
        // A device with no TTS engine installed at all throws from the
        // constructor on some OEM builds; guidance is then simply silent.
        tts = runCatching {
            TextToSpeech(appContext) { status -> onEngineInit(status) }
        }.getOrNull()
    }

    private fun onEngineInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        // Instructions come back from GraphHopper in English, so an English
        // voice is the one that pronounces them; the device language is the
        // fallback for a phone with no English voice data.
        val voice = listOf(Locale.US, Locale.UK, Locale.getDefault()).firstOrNull { locale ->
            when (runCatching { engine.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)) {
                TextToSpeech.LANG_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE,
                -> true
                else -> false
            }
        }
        runCatching {
            voice?.let { engine.setLanguage(it) }
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    main.post { abandonFocus() }
                }

                @Deprecated("Still abstract on the base class; the coded variant below is the real one")
                override fun onError(utteranceId: String?) {
                    main.post { abandonFocus() }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    main.post { abandonFocus() }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    main.post { abandonFocus() }
                }
            })
        }
        ready = true
        pending?.let { speak(it) }
        pending = null
    }

    /** Speaks [text], cutting off whatever was still being said — a prompt for
     *  the turn you are about to take always beats one for the turn behind it. */
    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: return
        if (!ready) {
            pending = text
            return
        }
        // A refused request means somebody else owns the output and said no to
        // sharing it: a phone call, an assistant, another navigation app.
        // Speaking anyway put a guidance prompt on top of a conversation — and
        // because abandonFocus() no-ops when the request failed, nothing was
        // ever handed back either. A missed prompt is the better of the two.
        if (!requestFocus()) {
            Log.w(TAG, "audio focus refused; guidance prompt dropped")
            return
        }
        val result = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }.getOrDefault(TextToSpeech.ERROR)
        // No utterance means no onDone, so nothing would hand the focus back.
        if (result != TextToSpeech.SUCCESS) abandonFocus()
    }

    /** Silences the current prompt — leaving the screen, or muting. */
    fun stop() {
        pending = null
        runCatching { tts?.stop() }
        abandonFocus()
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    /** True when the focus is ours. False means another client holds it and
     *  will not share — telephony during a call, a voice assistant, another
     *  turn-by-turn app. */
    private fun requestFocus(): Boolean {
        if (holdingFocus) return true
        holdingFocus = runCatching {
            audioManager?.requestAudioFocus(focusRequest)
        }.getOrNull() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return holdingFocus
    }

    private fun abandonFocus() {
        if (!holdingFocus) return
        holdingFocus = false
        runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }
    }
}
