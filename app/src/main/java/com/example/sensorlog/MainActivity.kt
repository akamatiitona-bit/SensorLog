package com.example.sensorlog

import android.app.Activity
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {
    private val G = 9.80665
    private lateinit var sm: SensorManager
    private lateinit var info: TextView
    private lateinit var toggle: Button
    private lateinit var oscInfo: TextView
    private lateinit var oscToggle: Button
    private lateinit var ipEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var permBtn: Button
    private val server = LatestServer(8765)

    private var acc: Sensor? = null
    private var gyro: Sensor? = null
    private var rot: Sensor? = null

    // ---- 記録 ----
    @Volatile private var running = false
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private val pa = DoubleArray(3)
    private var hasPa = false
    private var lastUi = 0L
    private var lines = 0L

    // ---- OSC ----
    private var oscRunning = false
    private var needCal = false
    private val rCal = DoubleArray(9)
    private val rCur = FloatArray(9)
    private var haveCur = false
    private var lastOsc = 0L
    private var lastOscUi = 0L
    private var rightFoot = true
    private var dummies = true
    private var perm = 0
    private val inv = booleanArrayOf(false, false, false)
    private val perms = listOf(
        intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
        intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0)
    )

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Store.init(filesDir)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rot = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }

        // === OSC (VRChat) ===
        root.addView(TextView(this).apply { text = "VRChat OSC (片足トラッカー)"; textSize = 18f })
        val sp = getSharedPreferences("osc", MODE_PRIVATE)
        ipEdit = EditText(this).apply {
            hint = "QuestのIPアドレス 例 192.168.0.25"
            setText(sp.getString("ip", ""))
        }
        portEdit = EditText(this).apply { hint = "ポート"; setText("9000") }
        root.addView(ipEdit)
        root.addView(portEdit)
        root.addView(Switch(this).apply {
            text = "右足 (オフで左足)"
            isChecked = true
            setOnCheckedChangeListener { _, on -> rightFoot = on }
        })
        root.addView(Switch(this).apply {
            text = "ダミーのトラッカー(反対の足と腰)も送る"
            isChecked = true
            setOnCheckedChangeListener { _, on -> dummies = on }
        })
        val axes = listOf("X", "Y", "Z")
        for (i in 0..2) {
            root.addView(Switch(this).apply {
                text = "回転 ${axes[i]} を反転"
                setOnCheckedChangeListener { _, on -> inv[i] = on }
            })
        }
        permBtn = Button(this).apply {
            text = "軸の入れ替え: 0,1,2"
            setOnClickListener {
                perm = (perm + 1) % perms.size
                text = "軸の入れ替え: ${perms[perm].joinToString(",")}"
            }
        }
        root.addView(permBtn)
        root.addView(Button(this).apply {
            text = "基準合わせ (まっすぐ立って押す)"
            setOnClickListener { needCal = true }
        })
        oscToggle = Button(this).apply {
            text = "OSC送信を開始"
            setOnClickListener { if (oscRunning) stopOsc() else startOsc() }
        }
        root.addView(oscToggle)
        oscInfo = TextView(this).apply { textSize = 14f; text = "停止中" }
        root.addView(oscInfo)

        // === 記録 ===
        root.addView(TextView(this).apply { text = "\n記録 (coordinate.txt)"; textSize = 18f })
        toggle = Button(this).apply {
            text = "記録を開始"
            setOnClickListener { if (running) stop() else start() }
        }
        root.addView(toggle)
        root.addView(Switch(this).apply {
            text = "まとめ書き(軽い) / オフで1行ごとに即書き"
            isChecked = true
            setOnCheckedChangeListener { _, on -> Store.batch = on }
        })
        root.addView(Button(this).apply {
            text = "最新500行を共有 (latest.txt)"
            setOnClickListener { share() }
        })
        info = TextView(this).apply { textSize = 16f; text = "停止中" }
        root.addView(info)
        root.addView(TextView(this).apply {
            textSize = 12f
            text = "\n行の形:\nA = 時刻,0,A,x,y,z (1G=約100)\nS = 時刻,0,S,横の傾き,前後の傾き,gx,gy,gz,加速度の変化\n\n" +
                "同じスマホ内から http://127.0.0.1:8765/latest?n=500 で最新行を取れます。\n" +
                "注意: 画面を離す(別のアプリを開く、画面が消える)とセンサーは止まります。"
        })
        setContentView(ScrollView(this).apply { addView(root) })
        server.start()
    }

    private fun updateKeepOn() {
        if (running || oscRunning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ================= OSC =================
    private fun startOsc() {
        val r = rot
        if (r == null) { oscInfo.text = "回転センサーがありません"; return }
        val ip = ipEdit.text.toString().trim()
        if (ip.isEmpty()) { oscInfo.text = "QuestのIPアドレスを入れてください"; return }
        val port = portEdit.text.toString().toIntOrNull() ?: 9000
        getSharedPreferences("osc", MODE_PRIVATE).edit().putString("ip", ip).apply()
        Osc.target(ip, port)
        haveCur = false
        needCal = true
        oscRunning = true
        sm.registerListener(this, r, 20000)
        oscToggle.text = "OSC送信を停止"
        oscInfo.text = "送信中 → $ip:$port"
        updateKeepOn()
    }

    private fun stopOsc() {
        oscRunning = false
        rot?.let { sm.unregisterListener(this, it) }
        oscToggle.text = "OSC送信を開始"
        oscInfo.text = "停止中"
        updateKeepOn()
    }

    private fun sendOsc() {
        val now = System.currentTimeMillis()
        if (needCal) {
            for (i in 0..8) rCal[i] = rCur[i].toDouble()
            needCal = false
        }
        if (now - lastOsc < 15) return
        lastOsc = now
        // M = Rcal^T * Rcur (基準の姿勢からの回転)
        val m = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += rCal[k * 3 + i] * rCur[k * 3 + j]
            m[i * 3 + j] = sum
        }
        val ex = Math.toDegrees(asin((-m[5]).coerceIn(-1.0, 1.0)))
        val ey = Math.toDegrees(atan2(m[2], m[8]))
        val ez = Math.toDegrees(atan2(m[3], m[4]))
        val e = doubleArrayOf(ex, ey, ez)
        val p = perms[perm]
        val out = FloatArray(3) { ((if (inv[it]) -1.0 else 1.0) * e[p[it]]).toFloat() }
        val side = if (rightFoot) 1f else -1f
        val msgs = ArrayList<ByteArray>()
        msgs.add(Osc.msg("/tracking/trackers/1/position", floatArrayOf(0.15f * side, 0.05f, 0f)))
        msgs.add(Osc.msg("/tracking/trackers/1/rotation", out))
        if (dummies) {
            msgs.add(Osc.msg("/tracking/trackers/2/position", floatArrayOf(-0.15f * side, 0.05f, 0f)))
            msgs.add(Osc.msg("/tracking/trackers/2/rotation", floatArrayOf(0f, 0f, 0f)))
            msgs.add(Osc.msg("/tracking/trackers/3/position", floatArrayOf(0f, 0.9f, 0f)))
            msgs.add(Osc.msg("/tracking/trackers/3/rotation", floatArrayOf(0f, 0f, 0f)))
        }
        Osc.send(Osc.bundle(msgs))
        if (now - lastOscUi > 200) {
            lastOscUi = now
            oscInfo.text = "送信中\n回転: ${f1(out[0].toDouble())}, ${f1(out[1].toDouble())}, ${f1(out[2].toDouble())}"
        }
    }

    // ================= 記録 =================
    private fun start() {
        val a = acc
        if (a == null) { info.text = "加速度センサーがありません"; return }
        hasPa = false
        running = true
        sm.registerListener(this, a, 20000)
        gyro?.let { sm.registerListener(this, it, 20000) }
        Thread {
            while (running) {
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                Store.flush()
            }
        }.apply { isDaemon = true; start() }
        toggle.text = "記録を停止"
        updateKeepOn()
    }

    private fun stop() {
        running = false
        acc?.let { sm.unregisterListener(this, it) }
        gyro?.let { sm.unregisterListener(this, it) }
        Store.flush()
        toggle.text = "記録を開始"
        info.text = "停止中 (書いた行数: $lines)"
        updateKeepOn()
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rCur, e.values)
                haveCur = true
                if (oscRunning) sendOsc()
                return
            }
            Sensor.TYPE_GYROSCOPE -> {
                gx = Math.toDegrees(e.values[0].toDouble())
                gy = Math.toDegrees(e.values[1].toDouble())
                gz = Math.toDegrees(e.values[2].toDouble())
                return
            }
            Sensor.TYPE_ACCELEROMETER -> {}
            else -> return
        }
        val a0 = e.values[0].toDouble()
        val a1 = e.values[1].toDouble()
        val a2 = e.values[2].toDouble()
        val now = System.currentTimeMillis()
        val k = 100.0 / G
        Store.add("$now,0,A,${(a0 * k).roundToInt()},${(a1 * k).roundToInt()},${(a2 * k).roundToInt()}")
        val roll = Math.toDegrees(atan2(a1, a2))
        val pitch = Math.toDegrees(atan2(-a0, hypot(a1, a2)))
        val da = if (hasPa) sqrt((a0 - pa[0]) * (a0 - pa[0]) + (a1 - pa[1]) * (a1 - pa[1]) + (a2 - pa[2]) * (a2 - pa[2])) * k else 0.0
        pa[0] = a0; pa[1] = a1; pa[2] = a2; hasPa = true
        Store.add("$now,0,S,${f1(roll)},${f1(pitch)},${f1(gx)},${f1(gy)},${f1(gz)},${f1(da)}")
        lines += 2
        if (now - lastUi > 100) {
            lastUi = now
            info.text = "横の傾き(roll): ${f1(roll)}°\n前後の傾き(pitch): ${f1(pitch)}°\n" +
                "加速度: ${(a0 * k).roundToInt()}, ${(a1 * k).roundToInt()}, ${(a2 * k).roundToInt()}\n" +
                "回転の速さ(度/秒): ${f1(gx)}, ${f1(gy)}, ${f1(gz)}\n" +
                "加速度の変化: ${f1(da)}\n書いた行数: $lines"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun share() {
        Store.flush()
        val f = File(filesDir, "latest.txt")
        f.writeText(Store.latest(500).joinToString("\n") + "\n")
        val uri = FileProvider.getUriForFile(this, "com.example.sensorlog.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "latest.txt を共有"))
    }

    override fun onPause() {
        if (running) stop()
        if (oscRunning) stopOsc()
        super.onPause()
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }
}
