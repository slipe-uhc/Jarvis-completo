package com.slipe.jarvis

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.util.Calendar

object Scheduler {
    private fun pending(c: Context, it: JSONObject?): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
        if (it != null) {
            i.putExtra("id", it.optString("id"))
            i.putExtra("title", it.optString("title"))
            i.putExtra("d", it.optString("d"))
            i.putExtra("snd", it.optBoolean("snd"))
            i.putExtra("w", it.optString("w"))
            i.putExtra("c", it.optString("c"))
        }
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Agenda o próximo item importante ainda não feito. Quando dispara, agenda o seguinte. */
    fun scheduleNext(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(c, null))
        if (!Core.prefs(c).getBoolean("notif", true)) return
        val now = System.currentTimeMillis()
        var best: JSONObject? = null
        var bestT = Long.MAX_VALUE
        for (off in 0..7) {
            val day = Calendar.getInstance()
            day.add(Calendar.DAY_OF_YEAR, off)
            val di = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7
            val date = Core.fmt(day)
            val arr = Core.items(c, di)
            for (k in 0 until arr.length()) {
                val it = arr.optJSONObject(k) ?: continue
                if (!it.optBoolean("imp")) continue
                if (off == 0 && Core.isDone(c, date, it.optString("id"))) continue
                val m = Core.toMin(it.optString("t"))
                val cal = day.clone() as Calendar
                cal.set(Calendar.HOUR_OF_DAY, m / 60)
                cal.set(Calendar.MINUTE, m % 60)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val t = cal.timeInMillis
                if (t > now + 3000 && t < bestT) { bestT = t; best = it }
            }
            if (best != null) break
        }
        val b = best ?: return
        val pi = pending(c, b)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bestT, pi)
            else
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bestT, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bestT, pi)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: ""
        if (id.isNotEmpty() && !Core.isDone(c, Core.today(), id)) {
            val cat = i.getStringExtra("c") ?: ""
            val w = i.getStringExtra("w") ?: ""
            val mode = Core.mode(c)
            val allowed = when (mode) {
                "estudo" -> cat == "study" || cat == "en"
                "treino" -> w.isNotEmpty()
                else -> true
            }
            if (allowed) {
                val title = i.getStringExtra("title") ?: "Jarvis"
                val d = i.getStringExtra("d") ?: ""
                Notifier.notify(c, title, if (d.isBlank()) "Agora" else d, i.getBooleanExtra("snd", false))
                if (mode == "treino" && w.isNotEmpty()) Core.openSpotify(c)
            }
        }
        Scheduler.scheduleNext(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Scheduler.scheduleNext(c)
    }
}
