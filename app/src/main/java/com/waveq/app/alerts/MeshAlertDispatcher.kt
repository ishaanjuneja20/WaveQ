package com.waveq.app.alerts

import android.content.Context
import android.util.Log
import com.waveq.app.mesh.MeshManager
import com.waveq.app.mesh.MessageType
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val ALERTED_BEACON_CAPACITY = 200

/**
 * Shared tag for every decision point on the alert path.
 *
 * One tag from mesh receipt through to the siren, so the whole chain is
 * greppable on a device with no debugger attached:
 *
 *     adb logcat -s AlertPath
 */
const val ALERT_PATH_TAG = "AlertPath"

/**
 * Process-scoped bridge from mesh traffic to the alert stack (siren,
 * notification, full-screen takeover).
 *
 * This deliberately does NOT live in MeshViewModel. A ViewModel is cleared the
 * moment its Activity goes away, so while the collectors lived there an
 * incoming CRITICAL flood alert or SOS beacon was still relayed by the
 * process-wide [MeshManager] but never surfaced to the user whenever no UI was
 * on screen - precisely the situation the alert stack exists for. The
 * ViewModel now only mirrors mesh traffic into UI state.
 *
 * TODO(design-decision-3): this covers the case where the process is still
 * alive (the app was swiped away but SosBeaconService, or a recently destroyed
 * Activity, kept it up). Surviving full process death requires a foreground
 * service that owns the mesh, which is an open design decision - see AUDIT.md,
 * "Needs a decision from you" item 3. Until that is decided, alerts stop when
 * the process does.
 */
object MeshAlertDispatcher {

    @Volatile private var started = false

    /** beaconIds already alerted on, so a repeating SOS fires the siren once per session, not every 30s. */
    private val alertedBeaconIds = object : LinkedHashMap<String, Boolean>(ALERTED_BEACON_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > ALERTED_BEACON_CAPACITY
    }

    /**
     * messageIds already alerted on.
     *
     * SOS had this protection and flood alerts did not. In P2P_CLUSTER every
     * device is connected to every other, so the same alert commonly arrives
     * from several peers at once - and each copy fired the takeover again.
     */
    private val alertedMessageIds = object : LinkedHashMap<String, Boolean>(ALERTED_BEACON_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > ALERTED_BEACON_CAPACITY
    }

    @Synchronized
    private fun claimFirstSighting(beaconId: String): Boolean {
        if (alertedBeaconIds.containsKey(beaconId)) return false
        alertedBeaconIds[beaconId] = true
        return true
    }

    @Synchronized
    private fun claimFirstMessage(messageId: String): Boolean {
        if (alertedMessageIds.containsKey(messageId)) return false
        alertedMessageIds[messageId] = true
        return true
    }

    /**
     * Starts the collectors on [scope], which must be process-scoped (owned by
     * MeshSession), never a viewModelScope. Idempotent.
     */
    @Synchronized
    fun start(context: Context, meshManager: MeshManager, myDeviceId: String, scope: CoroutineScope) {
        if (started) return
        started = true
        val appContext = context.applicationContext

        scope.launch {
            meshManager.incomingMessages.collect { message ->
                // Same shape as the SOS collector below: dedup first, then hand
                // to the one shared trigger. The trigger does its own type and
                // isMine filtering; anything else is a no-op.
                if (message.type != MessageType.FLOOD_ALERT) return@collect
                if (!claimFirstMessage(message.messageId)) {
                    Log.i(ALERT_PATH_TAG, "flood alert ${message.messageId} already alerted - dropping duplicate")
                    return@collect
                }
                Log.i(
                    ALERT_PATH_TAG,
                    "flood alert received: id=${message.messageId} severity=${message.severity} " +
                        "isMine=${message.isMine} from=${message.senderName}",
                )
                CriticalAlertTrigger.onFloodAlertReceived(appContext, message)
            }
        }

        scope.launch {
            meshManager.incomingSosBeacons.collect { beacon ->
                if (beacon.senderId == myDeviceId) {
                    Log.i(ALERT_PATH_TAG, "sos beacon ${beacon.beaconId} is our own - suppressed")
                    return@collect
                }
                if (!claimFirstSighting(beacon.beaconId)) return@collect
                Log.i(ALERT_PATH_TAG, "sos beacon received: id=${beacon.beaconId} from=${beacon.senderName}")
                // One presenter for every alert type. This used to also post
                // SosNotifications.notifyNewSos, which produced a second
                // notification on a channel with its own alarm ringtone - two
                // sounds for one event, only one of which any dismissal stopped.
                CriticalAlertTrigger.onSosBeaconReceived(appContext, beacon)
            }
        }
    }
}
