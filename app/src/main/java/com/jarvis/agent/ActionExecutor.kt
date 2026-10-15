package com.jarvis.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import org.json.JSONObject

object ActionExecutor {

    // Code-level guard on top of the prompt rules: never auto-tap things that commit you to something.
    private val BLOCKED_TAP_WORDS = listOf(
        "send", "pay", "buy", "purchase", "order", "delete", "remove", "confirm",
        "transfer", "uninstall", "erase", "reset", "submit", "checkout"
    )

    /** Runs one action. Returns a short note if it couldn't be done, otherwise null. */
    fun run(ctx: Context, a: JSONObject): String? = try {
        when (val type = a.optString("type")) {
            "open_app" -> openApp(ctx, a.optString("name"))
            "open_url" -> {
                val url = a.optString("url")
                if (url.startsWith("https://") || url.startsWith("http://"))
                    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                else "I can only open web links."
            }
            "web_search" -> start(
                ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.optString("query"))))
            )
            "dial" -> {
                val n = a.optString("number")
                if (Regex("^[+0-9()\\-\\s]{3,20}$").matches(n))
                    start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
                else "That phone number doesn't look right."
            }
            "sms" -> {
                val n = a.optString("number")
                if (Regex("^[+0-9()\\-\\s]{3,20}$").matches(n))
                    start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).putExtra("sms_body", a.optString("body")))
                else "That phone number doesn't look right."
            }
            "alarm" -> {
                val h = a.optInt("hour", -1)
                val m = a.optInt("minute", 0)
                if (h !in 0..23 || m !in 0..59) "That alarm time doesn't look right."
                else start(
                    ctx,
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, m)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
            }
            "timer" -> {
                val s = a.optInt("seconds", 0)
                if (s <= 0) "That timer length doesn't look right."
                else start(
                    ctx,
                    Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, s)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
            }
            "navigate" -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.optString("query")))))
            "flashlight" -> flashlight(ctx, a.optBoolean("on", true))
            "volume" -> volume(ctx, a)
            "settings" -> start(ctx, Intent(settingsAction(a.optString("page"))))
            "system" -> needService { it.globalAction(a.optString("do")) }
            "scroll" -> needService { it.scroll(a.optString("direction") != "up") }
            "tap_text" -> {
                val t = a.optString("text")
                val lower = t.lowercase()
                if (BLOCKED_TAP_WORDS.any { lower.contains(it) }) "I won't tap \"$t\" for you. Please do that one yourself."
                else needService { it.tapText(t) }
            }
            "type_text" -> needService { it.typeText(a.optString("text")) }
            else -> "I don't know how to do \"$type\"."
        }
    } catch (e: ActivityNotFoundException) {
        "No app on this phone can do that."
    } catch (e: Exception) {
        "That didn't work: ${e.message}"
    }

    private fun start(ctx: Context, intent: Intent): String? {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return null
    }

    private fun needService(block: (JarvisAccessibilityService) -> Boolean): String? {
        val s = JarvisAccessibilityService.instance
            ?: return "Turn on Jarvis in Accessibility settings so I can do that."
        return if (block(s)) null else "I couldn't do that on this screen."
    }

    private fun openApp(ctx: Context, name: String): String? {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val q = name.trim().lowercase()
        if (q.isEmpty()) return "Which app?"
        fun label(i: android.content.pm.ResolveInfo) = i.loadLabel(pm).toString().lowercase()
        val match = apps.firstOrNull { label(it) == q }
            ?: apps.filter { label(it).contains(q) }.minByOrNull { label(it).length }
            ?: apps.filter { label(it).length >= 4 && q.contains(label(it)) }.maxByOrNull { label(it).length }
            ?: return "I couldn't find an app called $name."
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I couldn't open $name."
        return start(ctx, intent)
    }

    private fun flashlight(ctx: Context, on: Boolean): String? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        return null
    }

    private fun volume(ctx: Context, a: JSONObject): String? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        if (a.has("level")) {
            am.setStreamVolume(stream, a.getInt("level").coerceIn(0, 100) * max / 100, AudioManager.FLAG_SHOW_UI)
            return null
        }
        when (a.optString("direction")) {
            "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
            "max" -> am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
            else -> return "Tell me up, down, or a level."
        }
        return null
    }

    private fun settingsAction(page: String): String = when (page) {
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "display" -> Settings.ACTION_DISPLAY_SETTINGS
        "sound" -> Settings.ACTION_SOUND_SETTINGS
        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        else -> Settings.ACTION_SETTINGS
    }
}
