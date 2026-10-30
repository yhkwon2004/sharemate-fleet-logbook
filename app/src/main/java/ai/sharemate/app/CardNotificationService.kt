package ai.sharemate.app

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads card approval alerts on the phone, the only moment a card company tells anyone about a payment in real time.
 * Android asks the user to grant this access in system settings. Everything that is not a card payment is dropped
 * inside the parser; for a payment only amount, merchant, time and last four digits move on, never the original text.
 */
class CardNotificationService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        if (sbn.packageName == packageName || sbn.isOngoing || notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
        val approval = parseCardNotification(title, text, sbn.postTime) ?: return
        scope.launch { PhoneSyncClient.get(this@CardNotificationService).enqueue(listOf(approval)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun granted(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
