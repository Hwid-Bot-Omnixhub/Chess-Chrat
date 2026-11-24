package com.example.chessreviewer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.example.BuildConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * Non-root Shizuku UserService bridge to [SecureShotService] via [ISecureShot].
 * Works over ADB / Wireless Debugging (uid 2000 shell) and bypasses FLAG_SECURE layers.
 * Uses Main-thread UserService dispatch + non-blocking retry cooldown so binding never hangs the UI.
 */
class ShizukuCapture(private val appContext: Context) {

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    private var service: ISecureShot? = null

    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    private var bindInFlight: Boolean = false

    @Volatile
    private var nextBindAttemptAtMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindLock = Any()

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, SecureShotService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("secureshot")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE + 12)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) {
                ISecureShot.Stub.asInterface(binder)
            } else {
                null
            }
            bindInFlight = false
            nextBindAttemptAtMs = 0L
            if (service != null) lastError = ""
            latch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bindInFlight = false
        }
    }

    fun isAppInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun ready(): Boolean = try {
        isRunning() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun isBound(): Boolean = service?.let { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) } == true

    @Volatile
    private var hasAttemptedBind: Boolean = false

    /** Pre-warms the Shizuku UserService connection asynchronously without blocking any caller. */
    fun warmUpAsync() {
        if (!ready() || isBound()) return
        val now = SystemClock.uptimeMillis()
        if (bindInFlight || (hasAttemptedBind && now < nextBindAttemptAtMs)) return
        synchronized(bindLock) {
            if (isBound() || bindInFlight || (hasAttemptedBind && SystemClock.uptimeMillis() < nextBindAttemptAtMs)) {
                return
            }
            hasAttemptedBind = true
            bindInFlight = true
            if (latch == null || latch?.count == 0L) {
                latch = CountDownLatch(1)
            }
            mainHandler.post {
                try {
                    Shizuku.bindUserService(userServiceArgs, connection)
                } catch (t: Throwable) {
                    bindInFlight = false
                    nextBindAttemptAtMs = SystemClock.uptimeMillis() + BIND_RETRY_COOLDOWN_MS
                    lastError = "Shizuku bind error: ${t.message ?: t.javaClass.simpleName}"
                    latch?.countDown()
                }
            }
        }
    }

    fun requestPermission(requestCode: Int = SHIZUKU_PERMISSION_REQUEST_CODE): Boolean {
        return try {
            if (!isRunning()) {
                lastError = if (isAppInstalled()) {
                    "Shizuku binder not connected yet — open Shizuku once or toggle its service"
                } else {
                    "Shizuku app is not installed"
                }
                false
            } else if (Shizuku.isPreV11()) {
                lastError = "Shizuku v11+ is required"
                false
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                nextBindAttemptAtMs = 0L
                warmUpAsync()
                true
            } else {
                Shizuku.requestPermission(requestCode)
                true
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    fun bindService(timeoutMs: Long = 5_000L): ISecureShot? {
        if (!ready()) {
            lastError = if (isRunning()) "Shizuku permission not granted" else "Shizuku is not running"
            return null
        }
        return bindIfNeeded(timeoutMs)
    }

    fun decodeFromSecureShot(svc: ISecureShot, width: Int = 0, height: Int = 0): Bitmap? {
        return try {
            val totalBytes = svc.capture(width, height)
            if (totalBytes <= 0) {
                val err = runCatching { svc.lastError() }.getOrNull().orEmpty()
                lastError = err.ifBlank { "Shizuku capture failed (code $totalBytes)" }
                return null
            }

            val out = ByteArrayOutputStream(totalBytes)
            var offset = 0
            while (offset < totalBytes) {
                val part = svc.chunk(offset, minOf(CHUNK_SIZE, totalBytes - offset))
                if (part == null || part.isEmpty()) break
                out.write(part)
                offset += part.size
            }
            val encoded = out.toByteArray()
            if (encoded.isEmpty()) {
                lastError = "Empty screenshot buffer from Shizuku"
                return null
            }
            val bmp = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            if (bmp == null) {
                lastError = "Failed to decode Shizuku screenshot"
            } else {
                lastError = ""
            }
            bmp
        } catch (_: DeadObjectException) {
            service = null
            bindInFlight = false
            nextBindAttemptAtMs = 0L
            lastError = "Shizuku user service disconnected; will reconnect"
            null
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            null
        }
    }

    fun grab(width: Int = 0, height: Int = 0, timeoutMs: Long = 5_000L): Bitmap? {
        val svc = bindService(timeoutMs) ?: return null
        return decodeFromSecureShot(svc, width, height)
    }

    private fun bindIfNeeded(timeoutMs: Long): ISecureShot? {
        val current = service
        if (current != null && runCatching { current.asBinder().pingBinder() }.getOrDefault(false)) {
            return current
        }
        service = null

        val now = SystemClock.uptimeMillis()
        if (hasAttemptedBind && now < nextBindAttemptAtMs) {
            return null
        }

        // Never await on the Main thread (would deadlock Shizuku's ServiceConnection callback)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            warmUpAsync()
            return null
        }

        val waitLatch = synchronized(bindLock) {
            val existing = service
            if (existing != null && runCatching { existing.asBinder().pingBinder() }.getOrDefault(false)) {
                return existing
            }
            val newLatch = latch?.takeIf { bindInFlight && it.count > 0 } ?: CountDownLatch(1).also {
                latch = it
            }
            if (!bindInFlight) {
                hasAttemptedBind = true
                bindInFlight = true
                mainHandler.post {
                    try {
                        Shizuku.bindUserService(userServiceArgs, connection)
                    } catch (t: Throwable) {
                        bindInFlight = false
                        nextBindAttemptAtMs = SystemClock.uptimeMillis() + BIND_RETRY_COOLDOWN_MS
                        lastError = "Could not bind Shizuku UserService: ${t.message ?: t.javaClass.simpleName}"
                        newLatch.countDown()
                    }
                }
            }
            newLatch
        }

        val bound = try {
            waitLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        val connected = service
        if (!bound || connected == null) {
            bindInFlight = false
            nextBindAttemptAtMs = SystemClock.uptimeMillis() + BIND_RETRY_COOLDOWN_MS
            lastError = "Shizuku service busy — retrying shortly (or use Standard Capture)"
            return null
        }
        return connected
    }

    @Synchronized
    fun unbind() {
        service = null
        bindInFlight = false
        nextBindAttemptAtMs = 0L
        mainHandler.post {
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        }
    }

    companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 4201
        private const val CHUNK_SIZE = 256 * 1024
        private const val BIND_RETRY_COOLDOWN_MS = 1_200L
    }
}
