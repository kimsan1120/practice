package com.kimsan.runexport

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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 15분마다 Health Connect 변경 내역을 확인해서 새 러닝이 들어오면 알림.
 * Health Connect 백그라운드 읽기 권한이 있어야 동작한다.
 */
class NewRunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) return Result.success()
        val prefs = Prefs(ctx)
        if (!prefs.profile().notifyNewRuns) return Result.success()
        val repo = HealthRepository(ctx)
        val granted = runCatching { repo.grantedPermissions() }.getOrDefault(emptySet())
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted ||
            HealthRepository.EXERCISE_PERMISSION !in granted
        ) return Result.success()

        return try {
            val (runs, next) = repo.newRunsSince(prefs.changesToken)
            prefs.changesToken = next
            runs.filter { !prefs.wasNotified(it.id) }.forEach { r ->
                notify(ctx, r)
                prefs.markNotified(r.id)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val CHANNEL = "new_runs"
        private const val WORK = "new_run_check"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (enabled) {
                val req = PeriodicWorkRequestBuilder<NewRunWorker>(15, TimeUnit.MINUTES).build()
                wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
            } else {
                wm.cancelUniqueWork(WORK)
            }
        }

        fun notificationsAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun notify(context: Context, r: RunSummary) {
            if (!notificationsAllowed(context)) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "새 러닝 기록", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "삼성 헬스에서 새 러닝이 들어오면 알려줘요"
                }
            )
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SESSION_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(
                context, r.id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = listOfNotNull(
                formatKm(r.distanceMeters),
                formatDuration(r.activeDuration),
                "${formatPace(r.paceSecPerKm)}/km",
                r.avgHr?.let { "심박 $it" },
            ).joinToString(" · ")
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("🏃 새 러닝 기록 (${r.start.localTime()})")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n눌러서 분석 보기 · 분석용 텍스트 복사"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(r.id.hashCode(), n) }
        }
    }
}
