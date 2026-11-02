package com.carlos.sleepsync

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {

    private val permissions = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))
    private var client: HealthConnectClient? = null

    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var daysInput: EditText
    private lateinit var logView: TextView

    private val permissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(permissions)) log("✔ Permiso de sueño concedido.")
            else log("✖ Permiso de sueño denegado.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadPrefs()

        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                client = HealthConnectClient.getOrCreate(this)
                log("Health Connect disponible.")
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                log("Health Connect necesita actualizarse desde Play Store.")
            else -> log("Health Connect no está disponible en este teléfono.")
        }
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(text: String) = TextView(this).apply { this.text = text }

        urlInput = EditText(this).apply {
            hint = "https://tu-backend.com/api/sleep (vacío = solo probar)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        tokenInput = EditText(this).apply { hint = "Token (opcional, se envía como Bearer)" }
        daysInput = EditText(this).apply {
            hint = "3"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("3")
        }

        val permBtn = Button(this).apply {
            text = "1. Dar permiso de sueño"
            setOnClickListener { requestPermission() }
        }
        val syncBtn = Button(this).apply {
            text = "2. Sincronizar"
            setOnClickListener { sync() }
        }

        logView = TextView(this).apply { setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(label("URL del backend"))
        root.addView(urlInput)
        root.addView(label("Token"))
        root.addView(tokenInput)
        root.addView(label("Días hacia atrás"))
        root.addView(daysInput)
        root.addView(permBtn)
        root.addView(syncBtn)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private fun prefs() = getSharedPreferences("sleepsync", Context.MODE_PRIVATE)

    private fun loadPrefs() {
        urlInput.setText(prefs().getString("url", ""))
        tokenInput.setText(prefs().getString("token", ""))
    }

    private fun savePrefs() {
        prefs().edit()
            .putString("url", urlInput.text.toString().trim())
            .putString("token", tokenInput.text.toString().trim())
            .apply()
    }

    private fun log(msg: String) {
        runOnUiThread { logView.append(msg + "\n") }
    }

    private fun requestPermission() {
        if (client == null) { log("Health Connect no está listo."); return }
        permissionLauncher.launch(permissions)
    }

    private fun sync() {
        val hc = client
        if (hc == null) { log("Health Connect no está listo."); return }
        savePrefs()
        val url = urlInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()
        val days = daysInput.text.toString().toLongOrNull()?.coerceIn(1, 30) ?: 3

        lifecycleScope.launch {
            try {
                val granted = hc.permissionController.getGrantedPermissions()
                if (!granted.containsAll(permissions)) {
                    log("Falta el permiso. Toca \"Dar permiso de sueño\".")
                    return@launch
                }

                log("Leyendo sueño de los últimos $days días…")
                val sessions = readSleep(hc, days)
                log("Sesiones encontradas: ${sessions.length()}")

                val payload = JSONObject().apply {
                    put("source", "health-connect")
                    put("synced_at", Instant.now().toString())
                    put("sessions", sessions)
                }

                if (url.isEmpty()) {
                    log("Sin URL: modo prueba. JSON:\n" + payload.toString(2))
                    return@launch
                }

                val code = withContext(Dispatchers.IO) { post(url, token, payload.toString()) }
                if (code in 200..299) log("✔ Enviado. Respuesta HTTP $code") else log("✖ El servidor respondió HTTP $code")
            } catch (e: Exception) {
                log("Error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun readSleep(hc: HealthConnectClient, days: Long): JSONArray {
        val end = Instant.now()
        val start = end.minus(days, ChronoUnit.DAYS)
        val out = JSONArray()
        var pageToken: String? = null
        do {
            val response = hc.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken
                )
            )
            response.records.forEach { out.put(toJson(it)) }
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return out
    }

    private fun stageName(type: Int) = when (type) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
        else -> "unknown"
    }

    private fun toJson(r: SleepSessionRecord): JSONObject {
        val stages = JSONArray()
        val totals = mutableMapOf<String, Long>()
        r.stages.forEach { s ->
            val name = stageName(s.stage)
            val mins = Duration.between(s.startTime, s.endTime).toMinutes()
            totals[name] = (totals[name] ?: 0L) + mins
            stages.put(JSONObject().apply {
                put("stage", name)
                put("start", s.startTime.toString())
                put("end", s.endTime.toString())
                put("minutes", mins)
            })
        }
        val totalsJson = JSONObject()
        totals.forEach { (k, v) -> totalsJson.put(k, v) }

        return JSONObject().apply {
            put("id", r.metadata.id)
            put("origin", r.metadata.dataOrigin.packageName)
            put("start", r.startTime.toString())
            put("end", r.endTime.toString())
            put("start_offset", r.startZoneOffset?.toString() ?: JSONObject.NULL)
            put("duration_minutes", Duration.between(r.startTime, r.endTime).toMinutes())
            put("title", r.title ?: JSONObject.NULL)
            put("notes", r.notes ?: JSONObject.NULL)
            put("stage_totals_minutes", totalsJson)
            put("stages", stages)
        }
    }

    private fun post(url: String, token: String, body: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
