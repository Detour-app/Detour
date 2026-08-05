package com.jellemax.detour.audio

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.jellemax.detour.net.ConvoyLiveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Push-to-talk capture and playback: raw 16kHz mono PCM16 over
 * [ConvoyLiveClient], no codec — a convoy of a handful of friends makes the
 * ~32kbps/speaker this costs trivial, and it skips MediaCodec/Opus entirely
 * for v1. `VOICE_COMMUNICATION`/`USAGE_VOICE_COMMUNICATION` plus
 * `MODE_IN_COMMUNICATION` (set by [com.jellemax.detour.convoy.ConvoyLiveService])
 * get hardware echo cancellation so playback doesn't loop back into the mic.
 */
object PushToTalk {

    private const val SAMPLE_RATE = 16_000

    /** 40ms per chunk: small enough to feel live, big enough that the
     *  base64+JSON envelope overhead per chunk doesn't dominate. */
    private const val CHUNK_SAMPLES = SAMPLE_RATE / 25
    private const val CHUNK_BYTES = CHUNK_SAMPLES * 2 // 16-bit PCM

    private var recordThread: Thread? = null
    @Volatile private var recording = false
    /** The AudioRecord the record thread currently owns, if any. Exposed so
     *  [stopTalking] can call [AudioRecord.stop] on it directly - that's
     *  what unblocks a `read()` call the record thread may be parked in,
     *  rather than hoping the 500ms join timeout is enough. */
    @Volatile private var activeRecord: AudioRecord? = null

    private var playbackJob: Job? = null
    private val tracks = HashMap<String, AudioTrack>()

    /** Starts capturing and streaming the mic while the PTT button is held.
     *  Call [stopTalking] on release. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startTalking() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK_BYTES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        activeRecord = record
        recording = true
        record.startRecording()
        ConvoyLiveClient.sendPttStart()
        recordThread = thread(name = "ptt-record") {
            val buf = ByteArray(CHUNK_BYTES)
            while (recording) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    ConvoyLiveClient.sendAudioChunk(if (read == buf.size) buf else buf.copyOf(read))
                }
            }
            record.stop()
            record.release()
            if (activeRecord === record) activeRecord = null
        }
    }

    fun stopTalking() {
        if (!recording) return
        recording = false
        // Unblocks a read() the record thread may currently be parked in -
        // AudioRecord.stop() is safe to call from another thread and causes
        // a pending read() to return promptly, so the join below is a
        // formality rather than a real wait against the 500ms cap.
        activeRecord?.stop()
        recordThread?.join(500)
        recordThread = null
        ConvoyLiveClient.sendPttEnd()
    }

    /** Starts playing back everyone else's PTT audio in [scope]; call once
     *  per convoy session (from the foreground service), not per press. */
    fun startPlayback(scope: CoroutineScope) {
        if (playbackJob != null) return
        playbackJob = scope.launch(Dispatchers.Default) {
            ConvoyLiveClient.audioChunks.collect { chunk ->
                trackFor(chunk.username).write(chunk.pcm, 0, chunk.pcm.size)
            }
        }
    }

    fun stopPlayback() {
        val job = playbackJob
        playbackJob = null
        val snapshot = synchronized(tracks) {
            val list = tracks.values.toList()
            tracks.clear()
            list
        }
        // AudioTrack.write() in MODE_STREAM blocks when its buffer is full,
        // and coroutine cancellation can't interrupt a blocking JNI call -
        // so release()ing immediately can race an in-flight write() and
        // crash with "Unable to retrieve AudioTrack pointer for write()".
        // stop() first unblocks any such pending write(); release() only
        // runs once the collector job has actually finished (whether it
        // was mid-write or idle), never concurrently with one.
        snapshot.forEach { runCatching { it.stop() } }
        if (job != null) {
            job.invokeOnCompletion { snapshot.forEach { runCatching { it.release() } } }
            job.cancel()
        } else {
            snapshot.forEach { runCatching { it.release() } }
        }
    }

    private fun trackFor(username: String): AudioTrack = synchronized(tracks) {
        tracks.getOrPut(username) {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { play() }
        }
    }
}
