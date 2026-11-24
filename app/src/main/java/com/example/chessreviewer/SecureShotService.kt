package com.example.chessreviewer

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * The code that actually reads a FLAG_SECURE screen.
 *
 * This class does not run in the app's process. Shizuku starts it in a process of its own,
 * with the uid of whoever started Shizuku: 2000 (shell) when Shizuku was brought up over ADB
 * or wireless debugging, 0 (root) when it was started as root. That process holds
 * android.permission.READ_FRAME_BUFFER, and one more thing is needed:
 *
 *   ScreenCapture.CaptureArgs.Builder#setCaptureSecureLayers(true)
 *
 * The class is loaded by name from Shizuku, so keep the package and the no-argument
 * constructor as they are.
 */
class SecureShotService : ISecureShot.Stub() {

    @Volatile private var pending: ByteArray? = null
    @Volatile private var error = ""

    override fun destroy() {
        try { System.exit(0) } catch (_: Throwable) { }
    }

    override fun capture(width: Int, height: Int): Int {
        pending = null
        error = ""
        val bitmap: Bitmap? = try {
            DisplayCapture.capture(width, height)
        } catch (t: Throwable) {
            error = describe(t)
            null
        }
        if (bitmap == null) {
            if (error.isBlank()) {
                error = DisplayCapture.lastError.ifBlank { "the system returned no frame" }
            }
            return -1
        }
        val bytes: ByteArray? = try {
            val out = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) out.toByteArray() else null
        } catch (t: Throwable) {
            error = "could not encode the frame: " + describe(t)
            null
        } finally {
            try { bitmap.recycle() } catch (_: Throwable) { }
        }
        if (bytes == null || bytes.isEmpty()) return -2
        pending = bytes
        return bytes.size
    }

    override fun chunk(offset: Int, length: Int): ByteArray {
        val all = pending ?: return ByteArray(0)
        if (offset < 0 || offset >= all.size || length <= 0) return ByteArray(0)
        val end = minOf(all.size, offset + length)
        return all.copyOfRange(offset, end)
    }

    override fun lastError(): String = error

    private fun describe(t: Throwable): String {
        var e: Throwable? = t
        var guard = 0
        while (e?.cause != null && e.cause !== e && guard < 6) {
            e = e.cause
            guard++
        }
        val real = e ?: t
        val name = real.javaClass.simpleName
        val message = real.message
        return if (message.isNullOrBlank()) name else "$name: $message"
    }
}
