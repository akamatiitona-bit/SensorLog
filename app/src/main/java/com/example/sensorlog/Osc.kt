package com.example.sensorlog

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** OSC(UDP)を作って送る。VRChatは 9000 番で受け取る。 */
object Osc {
    private val exec = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null
    private var ip = ""
    private var port = 9000

    fun target(ip: String, port: Int) {
        this.ip = ip
        this.port = port
    }

    private fun str(s: String): ByteArray {
        val b = s.toByteArray(Charsets.US_ASCII)
        return b.copyOf((b.size + 4) / 4 * 4)
    }

    fun msg(addr: String, v: FloatArray): ByteArray {
        val a = str(addr)
        val t = str("," + "f".repeat(v.size))
        val buf = ByteBuffer.allocate(a.size + t.size + 4 * v.size)
        buf.put(a).put(t)
        for (f in v) buf.putFloat(f)
        return buf.array()
    }

    fun bundle(msgs: List<ByteArray>): ByteArray {
        val head = str("#bundle")
        var size = head.size + 8
        for (m in msgs) size += 4 + m.size
        val buf = ByteBuffer.allocate(size)
        buf.put(head)
        buf.putLong(1L) // 即時
        for (m in msgs) { buf.putInt(m.size); buf.put(m) }
        return buf.array()
    }

    fun send(data: ByteArray) {
        val ip = this.ip
        val port = this.port
        exec.execute {
            try {
                val s = socket ?: DatagramSocket().also { socket = it }
                s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), port))
            } catch (_: Exception) {}
        }
    }
}
