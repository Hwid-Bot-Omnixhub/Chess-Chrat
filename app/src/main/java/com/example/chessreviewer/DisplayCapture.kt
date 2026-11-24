package com.example.chessreviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import java.lang.reflect.Method

/**
 * One display capture, one route per Android generation.
 *
 * This runs inside the Shizuku process (see [SecureShotService]), where hidden APIs are
 * reachable and the uid is shell (2000) or root.
 */
object DisplayCapture {

    enum class Route(val label: String) {
        NONE("no route"),
        SCREEN_CAPTURE("ScreenCapture.captureDisplay"),
        SURFACE_CONTROL("SurfaceControl.captureDisplay"),
        SCREENCAP("screencap")
    }

    @Volatile
    var route = Route.NONE
        private set

    @Volatile
    var lastError = ""
        private set

    fun capture(width: Int, height: Int): Bitmap? {
        val notes = ArrayList<String>(3)
        val size = if (width > 0 && height > 0) intArrayOf(width, height) else displaySize()
        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0) {
            lastError = "could not work out the screen size"
            return null
        }

        val routes = ArrayList<Pair<Route, () -> Bitmap>>(3)
        if (Build.VERSION.SDK_INT >= 31) {
            routes.add(Route.SCREEN_CAPTURE to { viaScreenCapture(w, h) })
        }
        if (Build.VERSION.SDK_INT >= 30) {
            routes.add(Route.SURFACE_CONTROL to { viaSurfaceControl(w, h) })
        }
        routes.add(Route.SCREENCAP to { viaScreencap() })

        var dark: Bitmap? = null
        for ((which, run) in routes) {
            val shot = attempt(which, notes, run) ?: continue
            if (looksBlack(shot)) {
                if (dark == null) dark = shot else shot.recycle()
                continue
            }
            dark?.recycle()
            lastError = ""
            return shot
        }
        lastError = notes.joinToString("; ").take(220)
        return dark
    }

    private inline fun attempt(which: Route, notes: MutableList<String>, block: () -> Bitmap): Bitmap? =
        try {
            block().also { route = which }
        } catch (t: Throwable) {
            notes.add(which.label + ": " + describe(t))
            null
        }

    private fun looksBlack(b: Bitmap): Boolean = try {
        val w = b.width
        val h = b.height
        if (w < 32 || h < 32) {
            false
        } else {
            var dark = 0
            var n = 0
            var y = 0
            val stepX = (w / 16).coerceAtLeast(1)
            val stepY = (h / 32).coerceAtLeast(1)
            while (y < h) {
                var x = 0
                while (x < w) {
                    val p = b.getPixel(x, y)
                    if (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255) < 12) dark++
                    n++
                    x += stepX
                }
                y += stepY
            }
            n > 0 && dark * 100 / n >= 85
        }
    } catch (_: Throwable) {
        false
    }

    private fun viaScreenCapture(w: Int, h: Int): Bitmap {
        val screenCapture = Class.forName("android.window.ScreenCapture")
        val listenerClass = Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")

        val listener = screenCapture.getMethod("createSyncCaptureListener").invoke(null)
            ?: error("no screen capture listener")
        val wms = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService")
            .invoke(null)
            ?: error("window manager service is not reachable")

        val args = buildCaptureArgs(w, h)
        val argsActualClass = args.javaClass

        val entry = wms.javaClass.methods.firstOrNull { m ->
            m.name == "captureDisplay" &&
                m.parameterTypes.size in 2..3 &&
                m.parameterTypes.all { p ->
                    p == Int::class.javaPrimitiveType ||
                        p.isAssignableFrom(argsActualClass) ||
                        p.isAssignableFrom(listenerClass)
                }
        } ?: error("this build has no captureDisplay entry point")

        val invocation = entry.parameterTypes.map { p ->
            when {
                p == Int::class.javaPrimitiveType -> 0
                p.isAssignableFrom(listenerClass) -> listener
                else -> args
            }
        }.toTypedArray()
        entry.invoke(wms, *invocation)

        val buffer = listener.javaClass.getMethod("getBuffer").invoke(listener)
            ?: error("the system returned no frame")
        return toBitmap(buffer)
    }

    private fun buildCaptureArgs(w: Int, h: Int): Any {
        val builderClass = try {
            Class.forName("android.window.ScreenCapture\$DisplayCaptureArgs\$Builder")
        } catch (_: Throwable) {
            Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
        }
        val builder = try {
            // DisplayCaptureArgs.Builder(IBinder displayToken) on some Android 14+ builds
            val displayToken = try {
                val scClass = Class.forName("android.view.SurfaceControl")
                scClass.getMethod("getInternalDisplayToken").invoke(null)
            } catch (_: Throwable) {
                null
            }
            val tokenCtor = if (displayToken != null) {
                builderClass.constructors.firstOrNull { c ->
                    c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(displayToken.javaClass)
                }
            } else {
                null
            }
            if (tokenCtor != null && displayToken != null) {
                tokenCtor.newInstance(displayToken)
            } else {
                builderClass.getDeclaredConstructor().newInstance()
            }
        } catch (_: Throwable) {
            Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
                .getDeclaredConstructor().newInstance()
        }
        call(builder, "setSourceCrop", Rect(0, 0, w, h))
        call(builder, "setSize", w, h)
        call(builder, "setDisplayId", 0)
        call(builder, "setCaptureSecureLayers", true)
        call(builder, "setAllowProtected", true)
        return call(builder, "build") ?: error("could not build the capture arguments")
    }

    private fun viaSurfaceControl(w: Int, h: Int): Bitmap {
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val token = surfaceControl.getMethod("getInternalDisplayToken").invoke(null)
            ?: error("no internal display token")
        val builderClass = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
        val builder = run {
            val byToken = builderClass.constructors.firstOrNull { c ->
                c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(token.javaClass)
            }
            if (byToken != null) {
                byToken.newInstance(token)
            } else {
                val tokenCtor = surfaceControl.declaredConstructors.firstOrNull { c ->
                    c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(token.javaClass)
                } ?: error("no display capture builder")
                tokenCtor.isAccessible = true
                val display = tokenCtor.newInstance(token)
                val byDisplay = builderClass.constructors.firstOrNull { c ->
                    c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(display.javaClass)
                } ?: error("no display capture builder")
                byDisplay.newInstance(display)
            }
        }

        call(builder, "setSourceCrop", Rect(0, 0, w, h))
        call(builder, "setSize", w, h)
        call(builder, "setPixelFormat", 1)
        call(builder, "setSecureLayersAllowed", true)
        val args = call(builder, "build") ?: error("could not build the capture arguments")

        val captureDisplay = methodFor(surfaceControl, "captureDisplay", arrayOf(args))
            ?: error("this build has no SurfaceControl.captureDisplay")
        val buffer = captureDisplay.invoke(null, args) ?: error("the system returned no frame")
        return toBitmap(buffer)
    }

    private fun viaScreencap(): Bitmap {
        val p = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
        val bytes = try {
            p.inputStream.readBytes()
        } finally {
            try { p.destroy() } catch (_: Throwable) { }
        }
        if (bytes.size < 8) error("screencap returned nothing")
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("could not decode the screencap")
    }

    private fun toBitmap(buffer: Any): Bitmap {
        val bitmap = try {
            buffer.javaClass.getMethod("asBitmap").invoke(buffer) as? Bitmap
        } catch (_: Throwable) {
            null
        } ?: error("the frame was not a bitmap")
        return try {
            bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: bitmap
        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun displaySize(): IntArray {
        val text = try {
            val p = Runtime.getRuntime().exec(arrayOf("wm", "size"))
            val out = p.inputStream.bufferedReader().readText()
            try { p.waitFor() } catch (_: Throwable) { }
            out
        } catch (_: Throwable) {
            ""
        }
        val match = Regex("size:\\s*(\\d+)x(\\d+)").findAll(text).lastOrNull()
            ?: return intArrayOf(0, 0)
        return intArrayOf(
            match.groupValues[1].toIntOrNull() ?: 0,
            match.groupValues[2].toIntOrNull() ?: 0
        )
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        try {
            methodFor(target.javaClass, name, args)?.invoke(target, *args)
        } catch (_: Throwable) {
            null
        }

    private fun methodFor(cls: Class<*>, name: String, args: Array<out Any?>): Method? {
        for (m in cls.methods) {
            if (m.name != name || m.parameterTypes.size != args.size) continue
            var fits = true
            for (i in args.indices) {
                val p = m.parameterTypes[i]
                val a = args[i]
                fits = when {
                    a == null -> !p.isPrimitive
                    p.isPrimitive -> boxed(p) == a.javaClass
                    else -> p.isAssignableFrom(a.javaClass)
                }
                if (!fits) break
            }
            if (fits) return m
        }
        return null
    }

    private fun boxed(p: Class<*>): Class<*>? = when (p) {
        Int::class.javaPrimitiveType -> Integer::class.java
        Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
        Float::class.javaPrimitiveType -> java.lang.Float::class.java
        else -> null
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
}
