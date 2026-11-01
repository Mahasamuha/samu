package com.mahasamuha.assistant.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Symfonium's documented broadcast API. Everything stays on the phone, so it works on any network.
 * Spike facts: names match exactly (case-insensitive), and an unknown name does nothing and reports
 * nothing back. That is why `play_music` resolves names through the server first.
 */
class Symfonium(
    private val context: Context,
) {
    val installed: Boolean
        get() =
            try {
                context.packageManager.getPackageInfo(PACKAGE, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }

    /** [type] is one of "album", "song", "artist", "playlist", "genre", "song_mix". */
    fun start(
        type: String,
        name: String?,
        artist: String?,
        album: String?,
        shuffle: Boolean,
    ) {
        send("app.symfonium.api.MEDIA_START") {
            putExtra("MEDIA_TYPE", type)
            name?.let { putExtra("NAME", it) }
            artist?.let { putExtra("ARTIST", it) }
            album?.let { putExtra("ALBUM", it) }
            if (shuffle) putExtra("SHUFFLE", true)
        }
    }

    /** [command] is one of "play", "pause", "next", "previous", "volume"; [value] goes with "volume". */
    fun command(
        command: String,
        value: Int? = null,
    ) {
        send("app.symfonium.api.MEDIA_COMMAND") {
            putExtra("COMMAND", command)
            value?.let { putExtra("INT_PARAMETER", it) }
        }
    }

    /** [type] 0 is this phone; otherwise a [SpeakerDirectory] type with its [identifier]. */
    fun selectRenderer(
        type: Int,
        identifier: String?,
    ) {
        send("app.symfonium.api.SELECT_RENDERER") {
            putExtra("TYPE", type)
            identifier?.let { putExtra("IDENTIFIER", it) }
        }
    }

    private fun send(
        action: String,
        extras: Intent.() -> Unit,
    ) {
        context.sendBroadcast(
            Intent(action).setComponent(ComponentName(PACKAGE, RECEIVER)).apply(extras),
        )
    }

    private companion object {
        const val PACKAGE = "app.symfonik.music.player"
        const val RECEIVER = "app.symfonium.remote.api.ApiReceiver"
    }
}
