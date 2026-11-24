package com.example.chessreviewer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.DeadObjectException
import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * Non-root Shizuku UserService bridge to [SecureShotService] via [ISecureShot].
 * Works over ADB / Wireless Debugging (uid 2000 shell) with zero root required.
 */
class ShizukuCapture(private val appContext: Context) {

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    private var service: ISecureShot? = null

    @Volatile
    private var latch: CountDownLatch? = null

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, SecureShotService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("secureshot")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) {
                ISecureShot.Stub.asInterface(binder)
            } else {
                null
            }
            latch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
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

    @Synchronized
    private fun bindIfNeeded(timeoutMs: Long): ISecureShot? {
        val current = service
        if (current != null && current.asBinder().pingBinder()) {
            return current
        }
        service = null
        val waitLatch = CountDownLatch(1)
        latch = waitLatch
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) {
            lastError = "Could not bind Shizuku UserService: ${t.message ?: t.javaClass.simpleName}"
            return null
        }
        val bound = try {
            waitLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!bound || service == null) {
            lastError = "Timed out binding Shizuku SecureShotService"
            return null
        }
        return service
    }

    @Synchronized
    fun unbind() {
        service = null
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
    }

    companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 4201
        private const val CHUNK_SIZE = 256 * 1024
    }
}
