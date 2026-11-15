package com.slipe.jarvis

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Core {
    fun prefs(c: Context) = c.getSharedPreferences("jv", Context.MODE_PRIVATE)

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    fun dayIdx(): Int = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
    fun fmt(cal: Calendar): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun nowMin(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun mode(c: Context): String = prefs(c).getString("mode", "normal") ?: "normal"
    fun ear(c: Context): String = prefs(c).getString("ear", "ambos") ?: "ambos"
    fun pair(c: Context): String = prefs(c).getString("pair", "") ?: ""
    fun voice(c: Context): Boolean = prefs(c).getBoolean("voice", false)
    fun soundOn(c: Context): Boolean = prefs(c).getBoolean("sound", true)

    fun sched(c: Context): JSONObject =
        try { JSONObject(prefs(c).getString("sched", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun items(c: Context, day: Int): JSONArray =
        sched(c).optJSONArray("days")?.optJSONArray(day) ?: JSONArray()

    fun isDone(c: Context, date: String, id: String): Boolean =
        sched(c).optJSONObject("done")?.optJSONObject(date)?.has(id) == true

    fun toMin(t: String): Int {
        val p = t.split(":")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** Retorna (item atual, próximo item) de hoje. */
    fun curNext(c: Context): Pair<JSONObject?, JSONObject?> {
        val now = nowMin()
        val arr = items(c, dayIdx())
        val date = today()
        var cur: JSONObject? = null
        var nxt: JSONObject? = null
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (isDone(c, date, it.optString("id"))) continue
            val m = toMin(it.optString("t"))
            if (m <= now) cur = it else if (nxt == null) nxt = it
        }
        if (cur != null && now - toMin(cur.optString("t")) > 90) cur = null
        return Pair(cur, nxt)
    }

    /* ---------- eventos para a interface (JS) ---------- */
    @Synchronized
    fun queueEvent(c: Context, o: JSONObject) {
        val arr = try { JSONArray(prefs(c).getString("events", "[]")) } catch (e: Exception) { JSONArray() }
        arr.put(o)
        prefs(c).edit().putString("events", arr.toString()).apply()
    }

    @Synchronized
    fun pullEvents(c: Context): String {
        val s = prefs(c).getString("events", "[]") ?: "[]"
        prefs(c).edit().putString("events", "[]").apply()
        return s
    }

    fun setMode(c: Context, m: String) {
        prefs(c).edit().putString("mode", m).apply()
        queueEvent(c, JSONObject().put("type", "mode").put("v", m))
        Scheduler.scheduleNext(c)
        JarvisService.instance?.refresh()
    }

    fun setEar(c: Context, e: String) {
        prefs(c).edit().putString("ear", e).apply()
        queueEvent(c, JSONObject().put("type", "ear").put("v", e))
        JarvisService.instance?.refresh()
    }

    fun markDone(c: Context, id: String, xp: Int) {
        val obj = sched(c)
        val done = obj.optJSONObject("done") ?: JSONObject().also { obj.put("done", it) }
        val date = today()
        val day = done.optJSONObject(date) ?: JSONObject().also { done.put(date, it) }
        if (xp > 0) day.put(id, xp) else day.remove(id)
        prefs(c).edit().putString("sched", obj.toString()).apply()
        queueEvent(c, JSONObject().put("type", "done").put("date", date).put("id", id).put("xp", xp))
        Scheduler.scheduleNext(c)
    }

    /* ---------- ligação PC <-> celular (ntfy.sh) ---------- */
    fun publish(c: Context, o: JSONObject) {
        val topic = pair(c)
        if (topic.isBlank()) return
        o.put("from", "celular")
        val body = o.toString()
        Thread {
            try {
                val u = URL("https://ntfy.sh/$topic")
                val cn = u.openConnection() as HttpURLConnection
                cn.requestMethod = "POST"
                cn.doOutput = true
                cn.connectTimeout = 10000
                cn.readTimeout = 10000
                cn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                cn.responseCode
                cn.disconnect()
            } catch (e: Exception) {
            }
        }.start()
    }

    fun handleRemote(c: Context, o: JSONObject): String? {
        if (o.optString("from") == "celular") return null
        when (o.optString("type")) {
            "ear" -> { setEar(c, o.optString("v", "ambos")) }
            "mode" -> { setMode(c, o.optString("v", "normal")) }
            "done" -> {
                val id = o.optString("id")
                val xp = o.optInt("xp", 0)
                val obj = sched(c)
                val done = obj.optJSONObject("done") ?: JSONObject().also { obj.put("done", it) }
                val date = o.optString("date", today())
                val day = done.optJSONObject(date) ?: JSONObject().also { done.put(date, it) }
                if (xp > 0) day.put(id, xp) else day.remove(id)
                prefs(c).edit().putString("sched", obj.toString()).apply()
                queueEvent(c, JSONObject().put("type", "done").put("date", date).put("id", id).put("xp", xp))
                Scheduler.scheduleNext(c)
            }
            "cmd" -> {
                val to = o.optString("to")
                if (to == "celular" || to == "all") return runCommand(c, o.optString("text"), true)
            }
        }
        return null
    }

    /* ---------- abrir apps e links ---------- */
    fun launch(c: Context, i: Intent, label: String) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val can = MainActivity.visible || Settings.canDrawOverlays(c)
        if (can) {
            try { c.startActivity(i); return } catch (e: Exception) { }
        }
        Notifier.notifyTap(c, label, i)
    }

    fun openUrl(c: Context, url: String, label: String) {
        launch(c, Intent(Intent.ACTION_VIEW, Uri.parse(url)), label)
    }

    fun openYoutube(c: Context, q: String) {
        val url = if (q.isBlank()) "https://www.youtube.com"
        else "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
        openUrl(c, url, if (q.isBlank()) "YouTube" else "YouTube: $q")
    }

    fun openSpotify(c: Context) {
        val i = c.packageManager.getLaunchIntentForPackage("com.spotify.music")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com"))
        launch(c, i, "Spotify")
    }

    fun openApp(c: Context, tab: String?) {
        val i = Intent(c, MainActivity::class.java)
        if (tab != null) i.putExtra("tab", tab)
        launch(c, i, "Jarvis")
    }

    /* ---------- comandos de voz ---------- */
    /** Executa o comando e devolve o texto que o Jarvis deve falar. */
    fun runCommand(c: Context, raw: String, fromRemote: Boolean = false): String {
        var n = norm(raw).replace(Regex("[.,!?]"), " ").replace(Regex("\\s+"), " ").trim()
        if (n.isEmpty()) return ""

        // mandar para o outro aparelho: "... no pc"
        if (!fromRemote && !n.contains("ouvido")) {
            val m = Regex("\\s(?:no|pro|para o|pra o)\\s+(pc|computador|notebook)\\s*$").find(n)
            if (m != null) {
                if (pair(c).isBlank()) return "Faça a ligação entre PC e celular nos Ajustes primeiro."
                val clean = raw.replace(Regex("\\s+(no|pro|para o|pra o)\\s+\\S+\\s*$", RegexOption.IGNORE_CASE), "")
                publish(c, JSONObject().put("type", "cmd").put("to", "pc").put("text", clean))
                return "Mandei para o PC"
            }
        }

        if (Regex("\\b(youtube|you tube)\\b").containsMatchIn(n)) {
            val m = Regex("(?:pesquis\\w*|busc\\w*|procur\\w*)\\s+(?:por\\s+)?(.+)$").find(n)
            val q = m?.groupValues?.get(1)?.replace(Regex("\\s+(no|pelo|na)\\s+(youtube|you tube)$"), "")?.trim() ?: ""
            openYoutube(c, q)
            return if (q.isBlank()) "Abrindo o YouTube" else "Pesquisando $q no YouTube"
        }
        if (n.contains("spotify")) { openSpotify(c); return "Abrindo o Spotify" }

        Regex("modo\\s+(silencioso|estudo|treino|normal)").find(n)?.let {
            val m = it.groupValues[1]
            setMode(c, m)
            publish(c, JSONObject().put("type", "mode").put("v", m))
            return "Modo $m ativado"
        }

        if (n.contains("ouvido") || Regex("(so|apenas)\\s+(ouvindo|ouve)").containsMatchIn(n)) {
            val has = Regex("(pc|computador|notebook|celular|telefone|ambos|os dois)").find(n)
            if (has != null) {
                val v = when {
                    Regex("(pc|computador|notebook)").containsMatchIn(n) -> "pc"
                    Regex("(celular|telefone)").containsMatchIn(n) -> "celular"
                    else -> "ambos"
                }
                setEar(c, v)
                publish(c, JSONObject().put("type", "ear").put("v", v))
                return when (v) {
                    "pc" -> "Agora só o PC escuta"
                    "celular" -> "Agora só o celular escuta"
                    else -> "Ouvindo nos dois aparelhos"
                }
            }
        }

        if (Regex("(que (eu )?(faco|tenho|devo)).*agora|agora.*(que|oque)|proximo").containsMatchIn(n) || n.startsWith("agora")) {
            val (cur, nxt) = curNext(c)
            val it = cur ?: nxt
            if (it == null) return "Você já fez tudo de hoje. Descanse."
            val d = it.optString("d")
            return (if (cur != null) "Agora: " else "Próximo às ${it.optString("t")}: ") + it.optString("title") + (if (d.isNotBlank()) ". $d" else "")
        }

        if (Regex("(mostr|abr|ver).*treino|treino de hoje|meu treino").containsMatchIn(n)) {
            openApp(c, "treino")
            return "Abrindo o treino"
        }

        if (Regex("\\b(marc\\w*|conclu\\w*|termin\\w*|fiz|feito|pronto)\\b").containsMatchIn(n)) {
            val (cur, nxt) = curNext(c)
            val it = cur ?: nxt ?: return "Não há item pendente."
            val xp = if (it.optBoolean("imp")) 20 else 10
            markDone(c, it.optString("id"), xp)
            publish(c, JSONObject().put("type", "done").put("date", today()).put("id", it.optString("id")).put("xp", xp))
            return "Marquei como feito: " + it.optString("title")
        }

        if (Regex("(para|pare|parar)\\s+(de\\s+)?ouvir").containsMatchIn(n)) {
            prefs(c).edit().putBoolean("voice", false).apply()
            queueEvent(c, JSONObject().put("type", "voice").put("v", false))
            JarvisService.instance?.stopAll()
            return "Parei de ouvir"
        }

        if (n.contains("ajuda") || n.contains("comandos"))
            return "Posso abrir YouTube e pesquisar, mudar de modo, dizer o que fazer agora, abrir o treino, marcar feito e trocar o ouvido entre PC e celular."
        if (Regex("\\b(oi|ola|e ai)\\b").containsMatchIn(n)) return "Estou aqui."
        return "Não entendi. Diga ajuda para ver os comandos."
    }
}
