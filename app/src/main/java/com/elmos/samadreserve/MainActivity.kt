package com.elmos.samadreserve

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccountInfo: TextView
    private lateinit var tvServiceState: TextView
    private lateinit var tvNextCheck: TextView
    private lateinit var tvServerBanner: TextView
    private lateinit var llDaysContainer: LinearLayout
    private lateinit var tvLogs: TextView
    private lateinit var svLogs: ScrollView
    private lateinit var switchAuto: SwitchMaterial
    private lateinit var actSelf: AutoCompleteTextView
    private lateinit var btnCheckNow: MaterialButton
    private lateinit var btnAccount: MaterialButton
    private lateinit var btnClearLogs: MaterialButton

    private var currentSelfList: List<SelfOption> = emptyList()

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Continue regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccountInfo = findViewById(R.id.tvAccountInfo)
        tvServiceState = findViewById(R.id.tvServiceState)
        tvNextCheck = findViewById(R.id.tvNextCheck)
        tvServerBanner = findViewById(R.id.tvServerBanner)
        llDaysContainer = findViewById(R.id.llDaysContainer)
        tvLogs = findViewById(R.id.tvLogs)
        svLogs = findViewById(R.id.svLogs)
        switchAuto = findViewById(R.id.switchAuto)
        actSelf = findViewById(R.id.actSelf)
        btnCheckNow = findViewById(R.id.btnCheckNow)
        btnAccount = findViewById(R.id.btnAccount)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        requestNotificationPermissionIfNeeded()

        val cachedSelfs = AppStateHolder.loadCachedSelfs(this)
        AppStateHolder.state.value = AppStateHolder.state.value.copy(selfOptions = cachedSelfs)
        updateSelfDropdown(cachedSelfs)
        refreshAccountHeader(AppStateHolder.state.value.credit)

        if (!hasCredentials()) {
            showLoginDialog(cancelable = false)
        }

        btnAccount.setOnClickListener { showLoginDialog(cancelable = true) }
        btnClearLogs.setOnClickListener { AppStateHolder.clearLogs() }

        actSelf.setOnItemClickListener { _, _, position, _ ->
            val picked = currentSelfList.getOrNull(position) ?: return@setOnItemClickListener
            getSharedPreferences("samad_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("self_id", picked.id)
                .apply()
            AppStateHolder.appendLog("Dining hall changed to ID ${picked.id}")
        }

        switchAuto.setOnClickListener {
            if (!hasCredentials()) {
                switchAuto.isChecked = false
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

    private fun refreshAccountHeader(credit: Long?) {
        val prefs = getSharedPreferences("samad_prefs", Context.MODE_PRIVATE)
        val u = prefs.getString("username", "")?.ifBlank { "NOT SET" } ?: "NOT SET"
        val creditStr = if (credit != null) {
            "${NumberFormat.getNumberInstance(Locale.US).format(credit)} IRR"
        } else {
            "—"
        }
        tvAccountInfo.text = "ID: $u  •  CREDIT: $creditStr"
    }

    private fun updateSelfDropdown(options: List<SelfOption>) {
        if (options.isEmpty() || options == currentSelfList) return
        currentSelfList = options
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, options)
        actSelf.setAdapter(adapter)

        val savedId = getSharedPreferences("samad_prefs", Context.MODE_PRIVATE)
            .getString("self_id", "29") ?: "29"
        val selected = options.firstOrNull { it.id == savedId } ?: options.first()
        actSelf.setText(selected.name, false)
    }

    private fun showLoginDialog(cancelable: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_login, null)
        val etUser = view.findViewById<TextInputEditText>(R.id.etUsername)
        val etPass = view.findViewById<TextInputEditText>(R.id.etPassword)

        val prefs = getSharedPreferences("samad_prefs", Context.MODE_PRIVATE)
        etUser.setText(prefs.getString("username", ""))
        etPass.setText(prefs.getString("password", ""))

        val dialog = AlertDialog.Builder(this)
            .setTitle("SAMADYAR ACCOUNT")
            .setView(view)
            .setCancelable(cancelable)
            .setPositiveButton("DONE", null)
            .apply {
                if (cancelable) setNegativeButton("CANCEL", null)
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = etUser.text?.toString()?.trim() ?: ""
                val p = etPass.text?.toString()?.trim() ?: ""
                if (u.isEmpty() || p.isEmpty()) {
                    etUser.error = if (u.isEmpty()) "Required" else null
                    etPass.error = if (p.isEmpty()) "Required" else null
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString("username", u)
                    .putString("password", p)
                    .apply()
                refreshAccountHeader(AppStateHolder.state.value.credit)
                AppStateHolder.appendLog("Account saved: $u")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun renderUiState(st: UiState) {
        refreshAccountHeader(st.credit)
        updateSelfDropdown(st.selfOptions)

        tvServiceState.text = st.statusText
        tvNextCheck.text = st.nextCheckText
        if (switchAuto.isChecked != st.isRunning) {
            switchAuto.isChecked = st.isRunning
        }

        tvServerBanner.text = st.serverMessage
        when (st.bannerStatus) {
            "success" -> {
                tvServerBanner.setBackgroundColor(Color.parseColor("#ECFDF5"))
                tvServerBanner.setTextColor(Color.parseColor("#065F46"))
            }
            "error" -> {
                tvServerBanner.setBackgroundColor(Color.parseColor("#FEF2F2"))
                tvServerBanner.setTextColor(Color.parseColor("#991B1B"))
            }
            else -> {
                tvServerBanner.setBackgroundColor(Color.parseColor("#F4F4F5"))
                tvServerBanner.setTextColor(Color.parseColor("#3F3F46"))
            }
        }

        llDaysContainer.removeAllViews()
        if (st.days.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No schedule loaded yet."
                setTextColor(Color.parseColor("#71717A"))
                textSize = 12.5f
            }
            llDaysContainer.addView(tv)
        } else {
            for (day in st.days) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(24, 18, 24, 18)
                    setBackgroundColor(Color.parseColor("#FAFAFA"))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = 10
                    layoutParams = lp
                }

                val leftTv = TextView(this).apply {
                    text = "${day.dayName.uppercase()}  ${day.jalaliDate}"
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#111111"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val (badgeText, bg, fg) = when (day.status) {
                    "OK" -> Triple("RESERVED", "#111111", "#FFFFFF")
                    "FAILED" -> Triple("FULL / FAILED", "#FEE2E2", "#991B1B")
                    "OPEN" -> Triple("OPEN", "#E4E4E7", "#111111")
                    else -> Triple("EMPTY", "#F4F4F5", "#71717A")
                }

                val badgeTv = TextView(this).apply {
                    text = badgeText
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(20, 8, 20, 8)
                    setBackgroundColor(Color.parseColor(bg))
                    setTextColor(Color.parseColor(fg))
                }

                row.addView(leftTv)
                row.addView(badgeTv)
                llDaysContainer.addView(row)
            }
        }

        tvLogs.text = st.logs.joinToString("\n")
        svLogs.post { svLogs.fullScroll(View.FOCUS_DOWN) }
    }
}
