package com.elmos.samadreserve

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val isRunning: Boolean = false,
    val statusText: String = "STANDBY",
    val nextCheckText: String = "Every 5m in background",
    val bannerStatus: String = "none",
    val serverMessage: String = "Tap CHECK NOW or toggle Auto Monitor.",
    val days: List<DayStatus> = emptyList(),
    val selfOptions: List<SelfOption> = SamadClient.DEFAULT_SELFS,
    val credit: Long? = null,
    val logs: List<String> = listOf("SamadYar ready.")
)

object AppStateHolder {
    val state = MutableStateFlow(UiState())

    fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$time] $msg"
        val current = state.value
        val updated = (current.logs + line).takeLast(150)
        state.value = current.copy(logs = updated)
    }

    fun clearLogs() {
        state.value = state.value.copy(logs = listOf("Logs cleared."))
    }

    fun loadCachedSelfs(context: Context): List<SelfOption> {
        val prefs = context.getSharedPreferences("samad_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("self_list_json", null) ?: return SamadClient.DEFAULT_SELFS
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SelfOption>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(SelfOption(obj.getString("id"), obj.getString("name")))
            }
            if (list.isNotEmpty()) list else SamadClient.DEFAULT_SELFS
        } catch (_: Exception) {
            SamadClient.DEFAULT_SELFS
        }
    }

    fun saveSelfs(context: Context, list: List<SelfOption>) {
        if (list.isEmpty()) return
        val arr = JSONArray()
        for (item in list) {
            arr.put(JSONObject().put("id", item.id).put("name", item.name))
        }
        context.getSharedPreferences("samad_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("self_list_json", arr.toString())
            .apply()
    }
}

class ReserveService : Service() {

    companion object {
        const val ACTION_START = "com.elmos.samadreserve.START"
        const val ACTION_STOP = "com.elmos.samadreserve.STOP"
        const val ACTION_CHECK_NOW = "com.elmos.samadreserve.CHECK_NOW"
        private const val CHANNEL_ID = "samad_reserve_channel"
        private const val NOTIF_ID = 1001
        private const val INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var samadClient: SamadClient

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        samadClient = SamadClient(
            onLog = { msg -> AppStateHolder.appendLog(msg) },
            onMetaUpdated = { selfs, credit ->
                if (selfs.isNotEmpty()) {
                    AppStateHolder.saveSelfs(applicationContext, selfs)
                }
                val cur = AppStateHolder.state.value
                AppStateHolder.state.value = cur.copy(
                    selfOptions = if (selfs.isNotEmpty()) selfs else cur.selfOptions,
                    credit = credit ?: cur.credit
                )
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_CHECK_NOW -> {
                val keepRunning = AppStateHolder.state.value.isRunning
                runImmediateOrLoop(singleShot = !keepRunning)
            }
            else -> {
                runImmediateOrLoop(singleShot = false)
            }
        }
        return START_STICKY
    }

    private fun runImmediateOrLoop(singleShot: Boolean) {
        val prefs = getSharedPreferences("samad_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("username", "")?.trim() ?: ""
        val password = prefs.getString("password", "")?.trim() ?: ""

        if (username.isEmpty() || password.isEmpty()) {
            AppStateHolder.appendLog("Error: Username/password not configured.")
            stopSelf()
            return
        }

        val notif = buildNotification(if (singleShot) "Checking now..." else "Auto monitor active...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        acquireWakeLock()

        loopJob?.cancel()
        loopJob = scope.launch {
            if (singleShot) {
                AppStateHolder.state.value = AppStateHolder.state.value.copy(
                    isRunning = false,
                    statusText = "CHECKING...",
                    nextCheckText = "Single check in progress"
                )
                val selfId = prefs.getString("self_id", "29")?.trim()?.ifEmpty { "29" } ?: "29"
                runOneCycle(username, password, selfId)
                releaseWakeLock()
                AppStateHolder.state.value = AppStateHolder.state.value.copy(
                    isRunning = false,
                    statusText = "STANDBY",
                    nextCheckText = "Every 5m in background"
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                AppStateHolder.state.value = AppStateHolder.state.value.copy(
                    isRunning = true,
                    statusText = "ACTIVE"
                )
                while (true) {
                    val selfId = prefs.getString("self_id", "29")?.trim()?.ifEmpty { "29" } ?: "29"
                    AppStateHolder.state.value = AppStateHolder.state.value.copy(statusText = "CHECKING...")
                    runOneCycle(username, password, selfId)

                    val nextTime = SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(Date(System.currentTimeMillis() + INTERVAL_MS))
                    AppStateHolder.state.value = AppStateHolder.state.value.copy(
                        isRunning = true,
                        statusText = "ACTIVE",
                        nextCheckText = "Next check at $nextTime"
                    )
                    AppStateHolder.appendLog("Sleeping 5m... Next check at $nextTime")
                    updateNotification("Self $selfId • Next check at $nextTime")
                    delay(INTERVAL_MS)
                }
            }
        }
    }

    private fun runOneCycle(username: String, password: String, selfId: String) {
        try {
            updateNotification("Checking Samad (Self $selfId)...")
            val res = samadClient.checkAndReserveNextWeek(username, password, selfId)
            AppStateHolder.state.value = AppStateHolder.state.value.copy(
                bannerStatus = res.bannerStatus,
                serverMessage = "${res.overallStatus}\n${res.serverMessage}",
                days = res.days,
                selfOptions = if (res.selfOptions.isNotEmpty()) res.selfOptions else AppStateHolder.state.value.selfOptions,
                credit = res.credit ?: AppStateHolder.state.value.credit
            )
            updateNotification(res.overallStatus)
        } catch (e: Exception) {
            val err = "Error: ${e.message ?: e::class.java.simpleName}"
            AppStateHolder.appendLog(err)
            AppStateHolder.state.value = AppStateHolder.state.value.copy(
                bannerStatus = "error",
                serverMessage = err
            )
        }
    }

    private fun stopMonitoring() {
        loopJob?.cancel()
        loopJob = null
        releaseWakeLock()
        AppStateHolder.appendLog("Auto monitor stopped.")
        AppStateHolder.state.value = AppStateHolder.state.value.copy(
            isRunning = false,
            statusText = "STANDBY",
            nextCheckText = "Every 5m in background"
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SamadYar::Lock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "SamadYar Background Monitor",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ReserveService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SamadYar")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        loopJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
