package com.example.chessreviewer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread

/**
 * Standard non-root Android screen capture via MediaProjection + VirtualDisplay.
 * Used on unrooted devices when Shizuku is not running.
 */
class ProjectionCapture {

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var wasNoNewFrame: Boolean = false
        private set

    @Volatile
    private var frameSequence: Long = 0L

    private val frameSignal = Object()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var width: Int = 0
    private var height: Int = 0
    private val lock = Any()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
        }
    }

    fun currentFrameSequence(): Long = frameSequence

    @SuppressLint("WrongConstant")
    fun start(mediaProjection: MediaProjection, screenWidth: Int, screenHeight: Int, densityDpi: Int) {
        stop()
        synchronized(lock) {
            if (screenWidth <= 0 || screenHeight <= 0) {
                lastError = "Invalid display dimensions for capture"
                return
            }
            width = screenWidth
            height = screenHeight
            frameSequence = 0L
            wasNoNewFrame = false

            val thread = HandlerThread("KnightSightProjection").apply { start() }
            val h = Handler(thread.looper)
            handlerThread = thread
            handler = h

            mediaProjection.registerCallback(projectionCallback, h)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({
                synchronized(frameSignal) {
                    frameSequence++
                    frameSignal.notifyAll()
                }
            }, h)
            val display = mediaProjection.createVirtualDisplay(
                "KnightSightBoardCapture",
                width,
                height,
                densityDpi.coerceAtLeast(160),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                h,
            )

            projection = mediaProjection
            imageReader = reader
            virtualDisplay = display
            isActive = true
            lastError = ""
        }
    }

    fun grab(waitAfterSeq: Long = -1L, waitForFreshMs: Long = 0L): Bitmap? {
        if (!isActive) return null
        if (waitAfterSeq >= 0L && waitForFreshMs > 0L) {
            val deadline = android.os.SystemClock.uptimeMillis() + waitForFreshMs
            synchronized(frameSignal) {
                while (isActive && frameSequence <= waitAfterSeq) {
                    val remaining = deadline - android.os.SystemClock.uptimeMillis()
                    if (remaining <= 0L) break
                    try {
                        frameSignal.wait(remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        synchronized(lock) {
            val reader = imageReader ?: return null
            if (!isActive) return null
            val image = try {
                reader.acquireLatestImage()
            } catch (t: Throwable) {
                wasNoNewFrame = false
                lastError = t.message ?: "ImageReader failed"
                null
            } ?: run {
                wasNoNewFrame = true
                lastError = "No screen update"
                return null
            }

            wasNoNewFrame = false
            return try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bmpWidth = width + rowPadding / pixelStride
                val full = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(buffer)
                val cropped = if (bmpWidth != width) {
                    Bitmap.createBitmap(full, 0, 0, width, height).also { full.recycle() }
                } else {
                    full
                }
                if (looksBlack(cropped)) {
                    cropped.recycle()
                    lastError = "Protected screen (FLAG_SECURE) blocked MediaProjection; use Shizuku"
                    null
                } else {
                    lastError = ""
                    cropped
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                null
            } finally {
                try {
                    image.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun looksBlack(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        if (w < 32 || h < 32) return false
        var dark = 0
        var n = 0
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = (h / 32).coerceAtLeast(1)
        var y = 0
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
        return n > 0 && dark * 100 / n >= 92
    }

    fun stop() {
        synchronized(lock) {
            isActive = false
            try {
                projection?.unregisterCallback(projectionCallback)
            } catch (_: Throwable) {
            }
            try {
                virtualDisplay?.release()
            } catch (_: Throwable) {
            }
            try {
                imageReader?.close()
            } catch (_: Throwable) {
            }
            try {
                projection?.stop()
            } catch (_: Throwable) {
            }
            try {
                handlerThread?.quitSafely()
            } catch (_: Throwable) {
            }
            virtualDisplay = null
            imageReader = null
            projection = null
            handlerThread = null
            handler = null
        }
    }
}
