package com.slipe.jarvis

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** Ponte entre a interface (JavaScript) e o Android. Nome no JS: Android */
class Bridge(private val a: Activity) {
    private val c: Context = a.applicationContext

    @JavascriptInterface
    fun sync(json: String) {
        try {
            val o = JSONObject(json)
            val ed = Core.prefs(c).edit()
            ed.putString("sched", JSONObject().put("days", o.optJSONArray("days")).put("done", o.optJSONObject("done") ?: JSONObject()).toString())
            ed.putBoolean("sound", o.optBoolean("sound", true))
            ed.putBoolean("notif", o.optBoolean("notif", true))
            ed.apply()
            Scheduler.scheduleNext(c)
        } catch (e: Exception) {
        }
    }

    @JavascriptInterface
    fun getState(): String {
        val p = Core.prefs(c)
        return JSONObject()
            .put("voice", Core.voice(c))
            .put("mode", p.getString("mode", "") ?: "")
            .put("ear", p.getString("ear", "") ?: "")
            .put("pair", Core.pair(c))
            .toString()
    }

    @JavascriptInterface
    fun pull(): String = Core.pullEvents(c)

    @JavascriptInterface
    fun setMode(m: String) {
        Core.prefs(c).edit().putString("mode", m).apply()
        Scheduler.scheduleNext(c)
        JarvisService.instance?.refresh()
    }

    @JavascriptInterface
    fun setEar(e: String) {
        Core.prefs(c).edit().putString("ear", e).apply()
        JarvisService.instance?.refresh()
    }

    @JavascriptInterface
    fun setPair(t: String) {
        Core.prefs(c).edit().putString("pair", t).apply()
        JarvisService.instance?.refresh()
    }

    @JavascriptInterface
    fun publish(json: String) {
        try { Core.publish(c, JSONObject(json)) } catch (e: Exception) { }
    }

    @JavascriptInterface
    fun startVoice() {
        Core.prefs(c).edit().putBoolean("voice", true).apply()
        val i = Intent(c, JarvisService::class.java)
        a.runOnUiThread {
            try { a.startForegroundService(i) } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun stopVoice() {
        Core.prefs(c).edit().putBoolean("voice", false).apply()
        JarvisService.instance?.stopAll()
    }

    @JavascriptInterface
    fun openUrl(u: String) {
        a.runOnUiThread { Core.openUrl(c, u, "Link") }
    }

    @JavascriptInterface
    fun notifyTest() {
        Notifier.notify(c, "Teste do Jarvis", "Se você viu isso, os avisos funcionam", true)
    }

    @JavascriptInterface
    fun askPerms() {
        a.runOnUiThread {
            val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            a.requestPermissions(perms.toTypedArray(), 1)
        }
    }

    @JavascriptInterface
    fun askOverlay() {
        a.runOnUiThread {
            a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.packageName)))
        }
    }

    @JavascriptInterface
    fun askExact() {
        a.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 31)
                a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + c.packageName)))
        }
    }

    @JavascriptInterface
    fun askBattery() {
        a.runOnUiThread {
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(c.packageName)) {
                a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName)))
            }
        }
    }
}
