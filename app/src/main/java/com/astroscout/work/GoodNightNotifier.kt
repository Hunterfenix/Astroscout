package com.astroscout.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.astroscout.MainActivity
import com.astroscout.R
import com.astroscout.domain.model.Spot
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Notifica "stanotte è una buona notte" per il miglior preferito sopra soglia. */
object GoodNightNotifier {
    const val CHANNEL_ID = "good_nights"
    private const val NOTIFICATION_ID = 4201

    fun createChannel(context: Context) {
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notif_channel_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Su Android 13+ serve il permesso runtime POST_NOTIFICATIONS. */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun notify(context: Context, spot: Spot) {
        if (!canNotify(context)) return
        val astro = spot.astro ?: return
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        fun fmt(i: java.time.Instant) = time.format(i.atZone(ZoneId.systemDefault()))

        val title = context.getString(R.string.notif_title, spot.name ?: context.getString(R.string.spot_unnamed), spot.score?.total ?: 0)
        val body = context.getString(
            R.string.notif_body,
            fmt(astro.dark.start), fmt(astro.dark.end),
            (astro.moonIllumination * 100).roundToInt(),
            spot.cloudPct?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "—",
        )
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // permesso revocato tra il controllo e la notify: niente notifica, nessun crash
        }
    }
}
