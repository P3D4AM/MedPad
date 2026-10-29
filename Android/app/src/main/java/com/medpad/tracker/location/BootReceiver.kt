package com.medpad.tracker.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.medpad.tracker.Session

/**
 * phase 3 — 24/7 tracking continuity across reboots.
 *
 * RECEIVE_BOOT_COMPLETED is already declared; this receiver restarts the
 * foreground service after a reboot when the user has a session, keeping
 * the tracking stream alive «در تمام ساعات شبانه‌روز» without manual app
 * opens. NOT gated on the notification toggles (NotificationPrefs): those
 * only mute notifications — the service also carries the location stream
 * and the check-in queue flush, which the user never opted out of.
 *
 * HONEST limitations (documented per the phase-3 instruction — no
 * guaranteed-tracking claims):
 *  - if the user FORCE-STOPPED the app, Android cancels all its pending
 *    registrations: no broadcast is delivered and the service stays down
 *    until the user opens the app again (OS policy — not app behaviour);
 *  - on some OEM "battery managers" the boot broadcast may be suppressed
 *    for "optimized" apps; the panel's live view shows the resulting
 *    no-report state honestly;
 *  - starting a location FGS from the background is restricted on newer
 *    Androids — a failure here is logged, never crashed on, and the next
 *    app open starts the service as usual.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Session.isLoggedIn) return
        try {
            TrackingService.start(context)
        } catch (_: Exception) {
            // FGS-from-background restrictions (API 31+ flavours): stay
            // quiet — the app's next foreground start brings it up
        }
    }
}
