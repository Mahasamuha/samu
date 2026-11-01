package com.mahasamuha.assistant.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SmsManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class SmsException(
    message: String,
) : Exception(message)

/**
 * Sends texts with the platform SmsManager (default SIM) and waits for the radio's sent result. As a
 * non-default SMS app, the system writes the sent message to the user's messaging history itself.
 */
class SmsSender(
    private val context: Context,
) {
    enum class Outcome { Sent, Pending }

    /** Returns [Outcome.Pending] if the radio hasn't reported within the timeout; throws [SmsException] on failure. */
    suspend fun send(
        number: String,
        message: String,
    ): Outcome {
        val sms = context.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(message)
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val results = Channel<Int>(parts.size)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    results.trySend(resultCode)
                }
            }
        // The PendingIntents fire as this app, so a non-exported receiver gets them.
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            val sentIntents =
                ArrayList(
                    parts.indices.map { i ->
                        PendingIntent.getBroadcast(
                            context,
                            i,
                            Intent(action).setPackage(context.packageName),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                        )
                    },
                )
            sms.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            return withTimeoutOrNull(SENT_TIMEOUT_MS) {
                repeat(parts.size) {
                    val code = results.receive()
                    if (code != Activity.RESULT_OK) throw SmsException("The text didn't send (${describe(code)})")
                }
                Outcome.Sent
            } ?: Outcome.Pending
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun describe(code: Int) =
        when (code) {
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "the radio is off"
            SmsManager.RESULT_ERROR_NULL_PDU, SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "the carrier refused it"
            else -> "error $code"
        }

    private companion object {
        const val SENT_TIMEOUT_MS = 30_000L
    }
}
