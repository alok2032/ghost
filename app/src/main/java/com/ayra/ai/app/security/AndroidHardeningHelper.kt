package com.ayra.ai.app.security

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Android Application Hardening utilities (Phase 2 & 10):
 * - FLAG_SECURE screen capture & task switcher thumbnail protection.
 * - Sensitive clipboard flagging (API 33+ EXTRA_IS_SENSITIVE) + timed auto-clear.
 * - Cryptographic temp-file byte overwriting before unlink.
 * - Overlay / tapjacking touch protection for sensitive actions.
 */
object AndroidHardeningHelper {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingClearRunnable: Runnable? = null
    private val secureRandom = SecureRandom()

    fun applyScreenCaptureProtection(activity: Activity?, enabled: Boolean) {
        if (activity == null) return
        runCatching {
            if (enabled) {
                activity.window?.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
                )
            } else {
                activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    /**
     * Copies text to the system clipboard with [ClipDescription.EXTRA_IS_SENSITIVE] on API 33+
     * and optionally schedules automatic clipboard wiping after [autoClearMs].
     */
    fun copyWithSensitivityProtection(
        context: Context,
        label: String,
        text: String,
        isSensitive: Boolean = true,
        autoClearMs: Long = 60_000L
    ) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText(label, text)
        if (isSensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)

        if (isSensitive && autoClearMs > 0L) {
            pendingClearRunnable?.let { mainHandler.removeCallbacks(it) }
            val expectedContent = text
            val clearTask = Runnable {
                runCatching {
                    val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
                    if (current == expectedContent) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            cm.clearPrimaryClip()
                        } else {
                            cm.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }
                }
            }
            pendingClearRunnable = clearTask
            mainHandler.postDelayed(clearTask, autoClearMs)
        }
    }

    fun readPlainTextFromClipboard(context: Context): String? {
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
            if (!cm.hasPrimaryClip()) return null
            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
    }

    /**
     * Overwrites temporary file bytes with cryptographically random data followed by zeros
     * before unlinking, mitigating lingering plaintext fragments in cache.
     * Note: Flash storage wear-leveling prevents kernel-level physical block guarantees.
     */
    fun secureWipeFile(file: File?): Boolean {
        if (file == null || !file.exists()) return true
        return try {
            if (file.isDirectory) {
                file.listFiles()?.forEach { secureWipeFile(it) }
                file.delete()
            } else {
                val len = file.length()
                if (len in 1L..32L * 1024L * 1024L) {
                    runCatching {
                        RandomAccessFile(file, "rws").use { raf ->
                            val buf = ByteArray(8192)
                            var remaining = len
                            raf.seek(0)
                            while (remaining > 0) {
                                secureRandom.nextBytes(buf)
                                val chunk = minOf(remaining, buf.size.toLong()).toInt()
                                raf.write(buf, 0, chunk)
                                remaining -= chunk
                            }
                            raf.fd.sync()
                            buf.fill(0)
                            remaining = len
                            raf.seek(0)
                            while (remaining > 0) {
                                val chunk = minOf(remaining, buf.size.toLong()).toInt()
                                raf.write(buf, 0, chunk)
                                remaining -= chunk
                            }
                            raf.fd.sync()
                        }
                    }
                }
                file.delete()
            }
        } catch (_: Exception) {
            runCatching { file.delete() }.getOrDefault(false)
        }
    }

    fun purgeTempCache(context: Context) {
        runCatching {
            context.cacheDir?.listFiles()?.forEach { child ->
                if (child.name != "code_cache") {
                    secureWipeFile(child)
                }
            }
        }
    }

    fun configureViewTapjackingProtection(view: View?) {
        view?.filterTouchesWhenObscured = true
    }
}

/**
 * Rejects pointer events when the window is partially or fully obscured by another app's overlay
 * (Defends sensitive security confirmations against Tapjacking / overlay attacks).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rejectObscuredTouches(onBlocked: (() -> Unit)? = null): Modifier =
    this.pointerInteropFilter { event ->
        val obscured = (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                (event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0)
        if (obscured) {
            onBlocked?.invoke()
            true // Consume and ignore the obscured tap
        } else {
            false
        }
    }
