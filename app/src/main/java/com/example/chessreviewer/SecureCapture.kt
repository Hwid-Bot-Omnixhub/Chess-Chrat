package com.example.chessreviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Reads screens including FLAG_SECURE layers without root when Shizuku is active,
 * falling back to root only if a working `su` binary exists.
 */
class SecureCapture(context: Context, cacheDir: File) {

    enum class Mode { UNKNOWN, ROOT, SHIZUKU, NONE }

    @Volatile var mode = Mode.UNKNOWN
        private set

    @Volatile var lastError = ""
        private set

    private val root = RootCapture(cacheDir)
    val shizuku = ShizukuCapture(context.applicationContext)

    @Volatile private var retryAfter = 0L
    @Volatile private var attemptedOnce = false

    /** Non-root first: prefers Shizuku whenever ready so unrooted phones never stall on `su`. */
    fun grab(width: Int = 0, height: Int = 0): Bitmap? {
        val now = SystemClock.uptimeMillis()
        if (attemptedOnce && now < retryAfter) return null
        attemptedOnce = true

        // Prioritize Shizuku (non-root ADB / Wireless Debugging) whenever ready or already active
        if (mode == Mode.SHIZUKU || shizuku.ready()) {
            val fromShizuku = shizuku.grab(width, height, if (mode == Mode.UNKNOWN) 5_000L else 3_000L)
            if (fromShizuku != null) {
                mode = Mode.SHIZUKU
                lastError = ""
                retryAfter = 0L
                return fromShizuku
            }
        }

        // Only try root if no Shizuku and device actually has a `su` binary
        if (mode != Mode.SHIZUKU && root.hasSuBinary()) {
            val fromRoot = root.grab()
            if (fromRoot != null) {
                mode = Mode.ROOT
                lastError = ""
                retryAfter = 0L
                return fromRoot
            }
        }

        mode = Mode.NONE
        lastError = shizuku.lastError.ifBlank { root.lastError.ifBlank { "No Shizuku or root capture available" } }
        retryAfter = now + RETRY_MS
        return null
    }

    fun release() {
        shizuku.unbind()
    }

    fun describe(): String = when {
        mode == Mode.SHIZUKU -> "Reading screen via Shizuku (non-root)."
        mode == Mode.ROOT -> "Reading screen via root."
        shizuku.ready() -> "Shizuku ready (non-root); protected boards supported."
        shizuku.isRunning() -> "Shizuku running — tap Grant Shizuku Access."
        else -> "Shizuku inactive — use Standard Screen Capture (No Root) below."
    }

    companion object {
        private const val RETRY_MS = 1_200L
    }
}

class RootCapture(private val cacheDir: File) {

    enum class Mode { UNKNOWN, ROOT, NONE }

    @Volatile var mode = Mode.UNKNOWN
        private set

    @Volatile var lastError = ""
        private set

    private var attempts = 0
    @Volatile private var current: Process? = null

    fun hasSuBinary(): Boolean {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
        )
        return candidates.any { File(it).let { f -> f.exists() && f.canExecute() } }
    }

    fun grab(): Bitmap? {
        if (mode == Mode.NONE) return null
        val first = attempts == 0
        attempts++
        val timeoutMs = if (first) 10_000L else 4_000L

        val holder = arrayOfNulls<ByteArray>(1)
        val worker = Thread { holder[0] = shootOnce(timeoutMs) }
        worker.isDaemon = true
        worker.start()
        try {
            worker.join(timeoutMs + 2_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (worker.isAlive) {
            lastError = "screencap did not finish in time"
            try { current?.destroy() } catch (_: Exception) { }
            return null
        }
        val bytes = holder[0] ?: return null
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) lastError = "could not decode the screenshot"
        return bitmap
    }

    private fun shootOnce(timeoutMs: Long): ByteArray? {
        val su = suBinary()
        if (su == null) {
            mode = Mode.NONE
            lastError = "no su binary"
            return null
        }
        if (mode != Mode.ROOT) {
            val id = readAll(su, "id")
            val text = id?.toString(Charsets.UTF_8) ?: ""
            if (!text.contains("uid=0")) {
                mode = Mode.NONE
                lastError =
                    if (text.isBlank()) "su did not answer (missing or denied)"
                    else "su is not root: " + text.trim().take(80)
                return null
            }
            mode = Mode.ROOT
        }
        val png = readAll(su, "screencap -p")
        if (png != null && isPng(png)) return png
        return shootToFile(su)
    }

    private fun shootToFile(su: String): ByteArray? {
        val out = File(cacheDir, "secure_shot.png")
        try { if (out.exists()) out.delete() } catch (_: Exception) { }
        val path = out.absolutePath
        readAll(su, "screencap -p $path; chmod 0644 $path 2>/dev/null")
        val bytes = try {
            if (out.exists() && out.length() > 8L) out.readBytes() else null
        } catch (_: Exception) {
            null
        }
        try { out.delete() } catch (_: Exception) { }
        return if (bytes != null && isPng(bytes)) bytes else null
    }

    private fun readAll(su: String, command: String): ByteArray? {
        val err = StringBuilder()
        var started: Process? = null
        try {
            val p = ProcessBuilder(su, "-c", command).start()
            started = p
            current = p

            val drain = Thread {
                try {
                    val r = InputStreamReader(p.errorStream)
                    val ca = CharArray(512)
                    var n = r.read(ca)
                    while (n > 0) {
                        if (err.length < 1024) err.append(ca, 0, n)
                        n = r.read(ca)
                    }
                } catch (_: Exception) {
                }
            }
            drain.isDaemon = true
            drain.start()

            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = p.inputStream.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) { lastError = "screenshot too large"; break }
                buf.write(chunk, 0, n)
            }
            p.waitFor()
            if (err.isNotBlank()) lastError = err.toString().trim().take(160)
            return buf.toByteArray()
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            return null
        } finally {
            current = null
            try { started?.destroy() } catch (_: Exception) { }
        }
    }

    private fun suBinary(): String? {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
        )
        for (c in candidates) {
            val f = File(c)
            if (f.exists() && f.canExecute()) return c
        }
        return null
    }

    private fun isPng(b: ByteArray): Boolean =
        b.size > 8 &&
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()

    private companion object {
        const val MAX_BYTES = 48 * 1024 * 1024
    }
}
