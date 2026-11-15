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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {
    private val G = 9.80665
    private lateinit var sm: SensorManager
    private lateinit var info: TextView
    private lateinit var toggle: Button
    private val server = LatestServer(8765)
    @Volatile private var running = false
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private val pa = DoubleArray(3)
    private var hasPa = false
    private var lastUi = 0L
    private var lines = 0L

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Store.init(filesDir)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
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
                "注意: 画面を離れる(別のアプリを開く)とセンサーは止まります。"
        })
        setContentView(ScrollView(this).apply { addView(root) })
        server.start()
    }

    private fun start() {
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) { info.text = "加速度センサーがありません"; return }
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        hasPa = false
        running = true
        sm.registerListener(this, acc, 20000)
        if (gyro != null) sm.registerListener(this, gyro, 20000)
        Thread {
            while (running) {
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
                Store.flush()
            }
        }.apply { isDaemon = true; start() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toggle.text = "記録を停止"
    }

    private fun stop() {
        running = false
        sm.unregisterListener(this)
        Store.flush()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toggle.text = "記録を開始"
        info.text = "停止中 (書いた行数: $lines)"
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            gx = Math.toDegrees(e.values[0].toDouble())
            gy = Math.toDegrees(e.values[1].toDouble())
            gz = Math.toDegrees(e.values[2].toDouble())
            return
        }
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
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
        super.onPause()
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }
}
