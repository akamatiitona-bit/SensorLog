package com.example.sensorlog

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** http://127.0.0.1:8765/latest?n=500 で最新の行を返す(同じスマホ内からだけ) */
class LatestServer(private val port: Int) {
    private var ss: ServerSocket? = null

    fun start() {
        thread(isDaemon = true) {
            try {
                val s = ServerSocket(port, 5, InetAddress.getByName("127.0.0.1"))
                ss = s
                while (true) {
                    val c = s.accept()
                    thread(isDaemon = true) { handle(c) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handle(c: Socket) {
        try {
            c.use {
                val r = it.getInputStream().bufferedReader()
                val first = r.readLine() ?: return
                var n = 500
                Regex("n=(\\d+)").find(first)?.let { m -> n = m.groupValues[1].toInt() }
                while (true) {
                    val l = r.readLine()
                    if (l.isNullOrEmpty()) break
                }
                val body = Store.latest(n).joinToString("\n").toByteArray()
                val o = it.getOutputStream()
                o.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Access-Control-Allow-Origin: *\r\nContent-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                o.write(body)
                o.flush()
            }
        } catch (_: Exception) {}
    }

    fun stop() { try { ss?.close() } catch (_: Exception) {} }
}
