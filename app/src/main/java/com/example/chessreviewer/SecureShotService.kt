package com.example.chessreviewer

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import kotlin.system.exitProcess

/**
 * The code that actually reads a FLAG_SECURE screen.
 *
 * This class runs in the privileged Shizuku UserService process (`uid 2000` shell over ADB /
 * wireless debugging, or `uid 0` if root). Supports both the Shizuku v13 Context constructor
 * and the no-argument constructor, and exempts hidden APIs so ScreenCapture/SurfaceControl work
 * on Android 12-16+.
 */
@Keep
class SecureShotService : ISecureShot.Stub {

    constructor() : super() {
        exemptHiddenApis()
    }

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super() {
        exemptHiddenApis()
    }

    @Volatile private var pending: ByteArray? = null
    @Volatile private var error = ""

    override fun destroy() {
        try {
            exitProcess(0)
        } catch (_: Throwable) {
        }
    }

    override fun capture(width: Int, height: Int): Int {
        exemptHiddenApis()
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

    private fun exemptHiddenApis() {
        if (hiddenApiExempted) return
        hiddenApiExempted = true
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            val runtime = getRuntime.invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            setExemptions.invoke(runtime, arrayOf("L") as Any)
        } catch (_: Throwable) {
        }
    }

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

    companion object {
        @Volatile
        private var hiddenApiExempted = false
    }
}
