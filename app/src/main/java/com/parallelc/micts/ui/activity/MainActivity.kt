package com.parallelc.micts.ui.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.provider.Settings
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.parallelc.micts.BuildConfig
import com.parallelc.micts.R
import com.parallelc.micts.config.AppConfig.CONFIG_NAME
import com.parallelc.micts.config.AppConfig.DEFAULT_CONFIG
import com.parallelc.micts.config.AppConfig.KEY_ASYNC_TRIGGER
import com.parallelc.micts.config.AppConfig.KEY_DEFAULT_DELAY
import com.parallelc.micts.config.AppConfig.KEY_TILE_DELAY
import com.parallelc.micts.config.AppConfig.KEY_VIBRATE
import com.parallelc.micts.module
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass

const val LOG_TAG = BuildConfig.APP_NAME

/** Reason of the last failed trigger, shown to the user to make "Trigger failed!" diagnosable. */
@Volatile
var lastTriggerFailure: String? = null

@SuppressLint("PrivateApi")
fun triggerCircleToSearch(entryPoint: Int, context: Context?, vibrate: Boolean): Boolean {
    lastTriggerFailure = null
    val result =  runCatching {
        val bundle = Bundle()
        if (BuildConfig.APP_NAME == "MiCTS") {
            bundle.putLong("invocation_time_ms", SystemClock.elapsedRealtime())
            bundle.putInt("omni.entry_point", entryPoint)
            bundle.putBoolean("micts_trigger", true)
        }
        val iVimsClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService")
        val vis = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "voiceinteraction")
        val vims = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, vis)
        // The signature of showSessionFromSession differs between Android versions
        // (and some ROMs), so pick the arguments from the actual parameter count.
        val paramCount = HiddenApiBypass.getDeclaredMethods(iVimsClass)
            .filter { it.name == "showSessionFromSession" }
            .map { it.parameterCount }
            .let { counts ->
                counts.firstOrNull { it == 4 || it == 3 }
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 4 else 3
            }
        if (paramCount >= 4) {
            HiddenApiBypass.invoke(iVimsClass, vims, "showSessionFromSession", null, bundle, 7, "hyperOS_home") as Boolean
        } else {
            HiddenApiBypass.invoke(iVimsClass, vims, "showSessionFromSession", null, bundle, 7) as Boolean
        }
    }.onFailure { e ->
        lastTriggerFailure = generateSequence<Throwable>(e) { it.cause }
            .joinToString("
  caused by ") { "${it.javaClass.name}: ${it.message}" }
        val errMsg = "triggerCircleToSearch invoke omni failed: " + e.stackTraceToString()
        module?.log(Log.ERROR, LOG_TAG, errMsg) ?: Log.e(LOG_TAG, errMsg)
    }.getOrDefault(false)
    if (!result && lastTriggerFailure == null) {
        lastTriggerFailure = "showSessionFromSession returned false (no voice interaction session was shown)"
    }
    if (result && vibrate && context != null) {
        runCatching {
            (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).run {
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setFlags(128)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK), attr)
                } else {
                    vibrate(longArrayOf(0, 1, 75, 76), -1, attr)
                }
            }
        }.onFailure { e ->
            val errMsg = "triggerCircleToSearch vibrate failed: " + e.stackTraceToString()
            module?.log(Log.ERROR, LOG_TAG, errMsg) ?: Log.e(LOG_TAG, errMsg)
        }
    }
    return result
}

@SuppressLint("PrivateApi")
private fun buildDiagnostics(context: Context): String {
    fun safe(block: () -> Any?) = runCatching { block()?.toString() }.getOrElse { "error: ${it.message}" }
    val signatures = safe {
        val iVims = Class.forName("com.android.internal.app.IVoiceInteractionManagerService")
        HiddenApiBypass.getDeclaredMethods(iVims)
            .filter { it.name == "showSessionFromSession" }
            .joinToString("; ") { m -> m.parameterTypes.joinToString(",", "(", ")") { it.simpleName } }
            .ifEmpty { "method not found" }
    }
    val assistant = safe { Settings.Secure.getString(context.contentResolver, "assistant") }
    val googleVersion = safe {
        context.packageManager.getPackageInfo("com.google.android.googlequicksearchbox", 0).versionName
    }
    return buildString {
        appendLine("${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME}")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Default assistant: $assistant")
        appendLine("Google app: $googleVersion")
        appendLine("showSessionFromSession: $signatures")
        append("Error: $lastTriggerFailure")
    }
}

class MainActivity : ComponentActivity() {
    private fun showFailureDialog() {
        val details = buildDiagnostics(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.trigger_failed)
            .setMessage(details)
            .setPositiveButton(R.string.copy_details) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("MiCTS", details))
                Toast.makeText(this, R.string.copy_details, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .setOnDismissListener { finish() }
            .show()
    }

    suspend fun delayAndTrigger(delayMs: Long, vibrate: Boolean) {
        if (delayMs > 0) {
            delay(delayMs)
        }
        if (triggerCircleToSearch(1, this, vibrate)) {
            finish()
        } else {
            showFailureDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val prefs = getSharedPreferences(CONFIG_NAME, MODE_PRIVATE)
        val key = if (intent.getBooleanExtra("from_tile", false)) KEY_TILE_DELAY else KEY_DEFAULT_DELAY
        val delayMs = prefs.getLong(key, DEFAULT_CONFIG[key] as Long)
        val vibrate = prefs.getBoolean(KEY_VIBRATE, DEFAULT_CONFIG[KEY_VIBRATE] as Boolean)

        if (prefs.getBoolean(KEY_ASYNC_TRIGGER, DEFAULT_CONFIG[KEY_ASYNC_TRIGGER] as Boolean)) {
            lifecycleScope.launch {
                delayAndTrigger(delayMs, vibrate)
            }
        } else {
            runBlocking {
                delayAndTrigger(delayMs, vibrate)
            }
        }
    }
}
