package com.example.sensorlog

import java.io.File

/** coordinate.txt を500行ごとに入れ替える。消さずに rename するので軽い。 */
object Store {
    private val lock = Any()
    private lateinit var cur: File
    private lateinit var prev: File
    private val buf = ArrayList<String>()
    private var count = 0
    @Volatile var batch = true

    fun init(dir: File) {
        cur = File(dir, "coordinate.txt")
        prev = File(dir, "coordinate.prev.txt")
        count = if (cur.exists()) cur.readLines().size else 0
    }

    fun add(line: String) = synchronized(lock) {
        buf.add(line)
        if (!batch) flushLocked()
    }

    fun flush() = synchronized(lock) { flushLocked() }

    private fun flushLocked() {
        if (buf.isEmpty()) return
        cur.appendText(buf.joinToString("\n") + "\n")
        count += buf.size
        buf.clear()
        if (count >= 500) {
            prev.delete()
            cur.renameTo(prev)
            count = 0
        }
    }

    fun latest(n: Int): List<String> = synchronized(lock) {
        val all = ArrayList<String>()
        if (prev.exists()) all.addAll(prev.readLines())
        if (cur.exists()) all.addAll(cur.readLines())
        all.takeLast(n)
    }
}
