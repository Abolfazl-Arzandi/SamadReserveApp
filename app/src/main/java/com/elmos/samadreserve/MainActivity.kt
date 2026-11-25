package com.elmos.samadreserve

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccountInfo: TextView
    private lateinit var tvServiceState: TextView
    private lateinit var tvServerBanner: TextView
    private lateinit var llDaysContainer: LinearLayout
    private lateinit var tvLogs: TextView
    private lateinit var svLogs: ScrollView
    private lateinit var btnStartStop: Button
    private lateinit var btnCheckNow: Button
    private lateinit var btnAccount: Button
    private lateinit var btnClearLogs: Button

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Continue regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccountInfo = findViewById(R.id.tvAccountInfo)
        tvServiceState = findViewById(R.id.tvServiceState)
        tvServerBanner = findViewById(R.id.tvServerBanner)
        llDaysContainer = findViewById(R.id.llDaysContainer)
        tvLogs = findViewById(R.id.tvLogs)
        svLogs = findViewById(R.id.svLogs)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnCheckNow = findViewById(R.id.btnCheckNow)
        btnAccount = findViewById(R.id.btnAccount)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        requestNotificationPermissionIfNeeded()
        refreshAccountHeader()

        if (!hasCredentials()) {
            showLoginDialog(cancelable = false)
        }

        btnAccount.setOnClickListener { showLoginDialog(cancelable = true) }
        btnClearLogs.setOnClickListener { AppStateHolder.clearLogs() }

        btnStartStop.setOnClickListener {
            if (!hasCredentials()) {
                showLoginDialog(cancelable = false)
                return@setOnClickListener
            }
            val intent = Intent(this, ReserveService::class.java)
            if (AppStateHolder.state.value.isRunning) {
                intent.action = ReserveService.ACTION_STOP
                startService(intent)
            } else {
                intent.action = ReserveService.ACTION_START
                ContextCompat.startForegroundService(this, intent)
            }
        }

        btnCheckNow.setOnClickListener {
            if (!hasCredentials()) {
                showLoginDialog(cancelable = false)
                return@setOnClickListener
            }
            val intent = Intent(this, ReserveService::class.java).apply {
                action = ReserveService.ACTION_CHECK_NOW
            }
            ContextCompat.startForegroundService(this, intent)
        }

        lifecycleScope.launch {
            AppStateHolder.state.collect { st ->
                renderUiState(st)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun hasCredentials(): Boolean {
        val prefs = getSharedPreferences("samad_prefs", Context.MODE_PRIVATE)
        val u = prefs.getString("username", "") ?: ""
        val p = prefs.getString("password", "") ?: ""
        return u.isNotBlank() && p.isNotBlank()
    }

    private fun refreshAccountHeader() {
        val prefs = getSharedPreferences("samad_prefs", Context.MODE_PRIVATE)
        val u = prefs.getString("username", "")?.ifBlank { "Not set" } ?: "Not set"
        val s = prefs.getString("self_id", "29")?.ifBlank { "29" } ?: "29"
        tvAccountInfo.text = "Account: $u  |  Self ID: $s"
    }

    private fun showLoginDialog(cancelable: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_login, null)
        val etUser = view.findViewById<TextInputEditText>(R.id.etUsername)
        val etPass = view.findViewById<TextInputEditText>(R.id.etPassword)
        val etSelf = view.findViewById<TextInputEditText>(R.id.etSelfId)

        val prefs = getSharedPreferences("samad_prefs", Context.MODE_PRIVATE)
        etUser.setText(prefs.getString("username", ""))
        etPass.setText(prefs.getString("password", ""))
        etSelf.setText(prefs.getString("self_id", "29"))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Samad Account")
            .setView(view)
            .setCancelable(cancelable)
            .setPositiveButton("Save", null)
            .apply {
                if (cancelable) setNegativeButton("Cancel", null)
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = etUser.text?.toString()?.trim() ?: ""
                val p = etPass.text?.toString()?.trim() ?: ""
                val s = etSelf.text?.toString()?.trim()?.ifEmpty { "29" } ?: "29"
                if (u.isEmpty() || p.isEmpty()) {
                    etUser.error = if (u.isEmpty()) "Required" else null
                    etPass.error = if (p.isEmpty()) "Required" else null
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString("username", u)
                    .putString("password", p)
                    .putString("self_id", s)
                    .apply()
                refreshAccountHeader()
                AppStateHolder.appendLog("Account saved: $u (Self $s)")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun renderUiState(st: UiState) {
        tvServiceState.text = "Status: ${st.statusText}"
        if (st.isRunning) {
            tvServiceState.setBackgroundColor(Color.parseColor("#DCFCE7"))
            tvServiceState.setTextColor(Color.parseColor("#166534"))
            btnStartStop.text = "Stop"
            btnStartStop.setBackgroundColor(Color.parseColor("#DC2626"))
        } else {
            tvServiceState.setBackgroundColor(Color.parseColor("#E5E7EB"))
            tvServiceState.setTextColor(Color.parseColor("#1F2937"))
            btnStartStop.text = "Start (Every 5m)"
            btnStartStop.setBackgroundColor(Color.parseColor("#2E7D32"))
        }

        tvServerBanner.text = st.serverMessage
        when (st.bannerStatus) {
            "success" -> {
                tvServerBanner.setBackgroundColor(Color.parseColor("#DBEAFE"))
                tvServerBanner.setTextColor(Color.parseColor("#1E40AF"))
            }
            "error" -> {
                tvServerBanner.setBackgroundColor(Color.parseColor("#FEE2E2"))
                tvServerBanner.setTextColor(Color.parseColor("#991B1B"))
            }
            else -> {
                tvServerBanner.setBackgroundColor(Color.parseColor("#FEF3C7"))
                tvServerBanner.setTextColor(Color.parseColor("#92400E"))
            }
        }

        llDaysContainer.removeAllViews()
        if (st.days.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No schedule loaded yet."
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 13f
            }
            llDaysContainer.addView(tv)
        } else {
            for (day in st.days) {
                val row = TextView(this).apply {
                    text = "${day.dayName} (${day.jalaliDate})  —  ${day.detail}"
                    textSize = 13f
                    setPadding(12, 10, 12, 10)
                    val (bg, fg) = when (day.status) {
                        "OK" -> "#DCFCE7" to "#166534"
                        "FAILED" -> "#FEE2E2" to "#991B1B"
                        "OPEN" -> "#FEF9C3" to "#854D0E"
                        else -> "#F3F4F6" to "#4B5563"
                    }
                    setBackgroundColor(Color.parseColor(bg))
                    setTextColor(Color.parseColor(fg))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = 6
                    layoutParams = lp
                }
                llDaysContainer.addView(row)
            }
        }

        tvLogs.text = st.logs.joinToString("\n")
        svLogs.post { svLogs.fullScroll(View.FOCUS_DOWN) }
    }
}
