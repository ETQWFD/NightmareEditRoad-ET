package io.github.xororz.localdream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.xororz.localdream.R

/**
 * 修图期间的前台保活服务。
 *
 * 批量修图的循环跑在应用内的控制器作用域里，熄屏或被系统回收时容易被判为“后台耗电任务”
 * 而杀掉进程（表现就是修图结果、队列和进度“突然被删除”）。这个服务只负责在整批任务期间
 * 维持前台优先级，并把进度显示在通知里；真正的生成仍在 EditBatchController 里执行。
 */
class EditKeepAliveService : Service() {

    companion object {
        private const val TAG = "EditKeepAlive"
        private const val CHANNEL_ID = "editroad_edit_channel"
        private const val NOTIFICATION_ID = 40219

        @Volatile
        private var latestText: String = ""

        @Volatile
        private var latestDone: Int = 0

        @Volatile
        private var latestTotal: Int = 0

        @Volatile
        private var running = false

        fun start(context: Context, text: String, done: Int, total: Int) {
            latestText = text
            latestDone = done
            latestTotal = total
            if (running) {
                update(context, text, done, total)
                return
            }
            running = true
            runCatching {
                context.startForegroundService(
                    Intent(context, EditKeepAliveService::class.java),
                )
            }.onFailure { Log.w(TAG, "start failed", it) }
        }

        fun update(context: Context, text: String, done: Int, total: Int) {
            if (!running) return
            latestText = text
            latestDone = done
            latestTotal = total
            runCatching {
                context.startService(Intent(context, EditKeepAliveService::class.java))
            }.onFailure { Log.w(TAG, "update failed", it) }
        }

        /** 结束时留一条完成/失败通知，然后撤销前台状态。 */
        fun finish(context: Context, text: String) {
            val app = context.applicationContext
            runCatching {
                val nm = app.getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID + 1, summaryNotification(app, text))
            }
            running = false
            runCatching {
                app.startService(
                    Intent(app, EditKeepAliveService::class.java).setAction(ACTION_STOP),
                )
            }
        }

        private const val ACTION_STOP = "etc.editroad.et.action.STOP_KEEPALIVE"

        private const val ACTION_STOP_EDIT = "etc.editroad.et.action.STOP_EDIT"

        private fun stopEditIntent(context: Context): PendingIntent? = runCatching {
            PendingIntent.getService(
                context,
                1,
                Intent(context, EditKeepAliveService::class.java).setAction(ACTION_STOP_EDIT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrNull()

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "本地修图",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "修图进行中保持任务不被系统回收"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        private fun openAppIntent(context: Context): PendingIntent? {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK }
                ?: return null
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        private fun summaryNotification(context: Context, text: String): Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("NERT 修图")
                .setContentText(text)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_EDIT) {
            io.github.xororz.localdream.ui.screens.EditBatchController.stop(this)
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("NERT 正在修图")
            .setContentText(latestText)
            .setProgress(latestTotal.coerceAtLeast(1), latestDone, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(this))
            .addAction(0, "停止修图", stopEditIntent(this))
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onFailure {
                Log.w(TAG, "startForeground rejected: ${it.message}")
                runCatching {
                    @Suppress("DEPRECATION")
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
