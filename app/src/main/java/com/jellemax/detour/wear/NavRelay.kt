package com.jellemax.detour.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.jellemax.detour.data.NavEngine
import org.json.JSONObject

private const val NAV_PATH = "/nav"

/** Pushes turn-by-turn state to a paired Wear OS watch via the Message API. */
object NavRelay {
    private var lastSentAt = 0L
    private var lastSign: Int? = null

    fun send(context: Context, progress: NavEngine.Progress, currentSpeedKmh: Double) {
        val instruction = progress.nextInstruction
        val now = System.currentTimeMillis()
        // Throttle: only push on a new maneuver, or at most once/second otherwise.
        if (instruction?.sign == lastSign && now - lastSentAt < 1000) return
        lastSentAt = now
        lastSign = instruction?.sign

        val payload = JSONObject().apply {
            put("sign", instruction?.sign ?: 0)
            put("text", instruction?.text ?: "")
            put("distanceToTurnMeters", progress.distanceToTurnMeters)
            put("speedKmh", currentSpeedKmh)
            put("speedLimitKmh", progress.speedLimitKmh ?: JSONObject.NULL)
        }.toString().toByteArray()
        broadcast(context, payload)
    }

    fun clear(context: Context) {
        lastSign = null
        val payload = JSONObject().apply { put("stop", true) }.toString().toByteArray()
        broadcast(context, payload)
    }

    private fun broadcast(context: Context, payload: ByteArray) {
        val messageClient = Wearable.getMessageClient(context)
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node -> messageClient.sendMessage(node.id, NAV_PATH, payload) }
        }
    }
}
