package com.mahasamuha.assistant.navigation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.mahasamuha.assistant.voice.TAG

/**
 * Invisible: shows Play services' one-tap "turn on location?" dialog, then opens the maps app if the
 * user agreed. The session starts it and closes; an activity on screen may launch the maps app
 * afterwards, which a closing panel may not (Android 17 background launch rules). It takes the
 * destination and app, not an intent, so it never launches an intent handed to it.
 */
class TurnOnLocationActivity : ComponentActivity() {
    private val turnOn =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) navigate() else Log.i(TAG, "Location left off; not navigating")
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the dialog is already up
        val request =
            LocationSettingsRequest
                .Builder()
                .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000).build())
                .setAlwaysShow(true)
                .setNeedBle(false)
                .build()
        LocationServices
            .getSettingsClient(this)
            .checkLocationSettings(request)
            .addOnSuccessListener {
                navigate()
                finish()
            }.addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    turnOn.launch(IntentSenderRequest.Builder(e.resolution).build())
                } else {
                    Log.w(TAG, "Location settings check failed", e)
                    finish()
                }
            }
    }

    private fun navigate() {
        val destination = intent.getStringExtra(EXTRA_DESTINATION) ?: return
        val app = MapsApp.entries.firstOrNull { it.id == intent.getStringExtra(EXTRA_APP) } ?: return
        startActivity(app.intent(destination))
    }

    companion object {
        private const val EXTRA_DESTINATION = "destination"
        private const val EXTRA_APP = "app"

        fun intent(
            context: Context,
            destination: String,
            app: MapsApp,
        ): Intent =
            Intent(context, TurnOnLocationActivity::class.java)
                .putExtra(EXTRA_DESTINATION, destination)
                .putExtra(EXTRA_APP, app.id)
    }
}
