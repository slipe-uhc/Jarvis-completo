package com.slipe.jarvis

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Fica ouvindo "Jarvis" em segundo plano (serviço em primeiro plano, com notificação fixa). */
class JarvisService : Service() {
    companion object {
        @Volatile var instance: JarvisService? = null
        const val ACTION_STOP = "com.slipe.jarvis.STOP"
        private val WAKE = Regex("\\b(jarvis|jarves|garvis|jarviz|jarbis|charvis)\\b(.*)$")
    }

    private val h = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var awaitingUntil = 0L
    private var listening = false
    private var speaking = false
    @Volatile private var subRun = false
    private var subThread: Thread? = null
    private var subTopic = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifier.ensure(this)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { speaking = false; h.postDelayed({ listenNow() }, 400) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { speaking = false; h.postDelayed({ listenNow() }, 400) }
                })
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Core.prefs(this).edit().putBoolean("voice", false).apply()
            Core.queueEvent(this, JSONObject().put("type", "voice").put("v", false))
            stopAll()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        refresh()
        return START_STICKY
    }

    private fun statusText(): String {
        val ear = Core.ear(this)
        return when {
            ear == "pc" -> "Em espera: o PC é o ouvido agora"
            else -> "Ouvindo \"Jarvis\"… (modo ${Core.mode(this)})"
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 5, Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Jarvis")
            .setContentText(statusText())
            .setOngoing(true)
            .setContentIntent(Notifier.openPending(this))
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Parar", stopPi).build())
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    /** Reaplica estado: escuta ou não, e conexão com o PC. */
    fun refresh() {
        h.post {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, buildNotification())
            if (wantListen()) listenNow() else stopRec()
            startSub()
        }
    }

    private fun wantListen(): Boolean {
        val ear = Core.ear(this)
        return Core.voice(this) && (ear == "celular" || ear == "ambos")
    }

    /* ---------- reconhecimento de voz ---------- */
    private fun ensureRec() {
        if (sr != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Notifier.notify(this, "Jarvis", "Este celular não tem reconhecimento de voz (instale/atualize o app Google).", false)
            return
        }
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: android.os.Bundle?) {}
            override fun onEvent(t: Int, p: android.os.Bundle?) {}
            override fun onError(e: Int) {
                listening = false
                val delay = if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2000L else 700L
                h.postDelayed({ listenNow() }, delay)
            }
            override fun onResults(r: android.os.Bundle?) {
                listening = false
                val list = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val txt = list?.firstOrNull()
                if (!txt.isNullOrBlank()) heard(txt)
                h.postDelayed({ listenNow() }, 300)
            }
        })
    }

    private fun listenNow() {
        if (!wantListen() || speaking || listening) return
        ensureRec()
        val rec = sr ?: return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        try { rec.startListening(i); listening = true } catch (e: Exception) { listening = false }
    }

    private fun stopRec() {
        listening = false
        try { sr?.cancel() } catch (e: Exception) { }
    }

    private fun heard(text: String) {
        val n = Core.norm(text)
        val m = WAKE.find(n)
        if (m != null) {
            val rest = m.groupValues[2].trim()
            if (rest.isNotEmpty()) { awaitingUntil = 0; execute(rest) }
            else { awaitingUntil = System.currentTimeMillis() + 7000; speak("Pois não?") }
        } else if (awaitingUntil > System.currentTimeMillis()) {
            awaitingUntil = 0
            execute(text)
        }
    }

    private fun execute(cmd: String) {
        val reply = try { Core.runCommand(this, cmd) } catch (e: Exception) { "Deu erro nesse comando." }
        if (reply.isNotBlank()) speak(reply)
    }

    private fun speak(t: String) {
        val quiet = !Core.soundOn(this) || Core.mode(this) == "silencioso"
        if (quiet || !ttsReady) return
        speaking = true
        stopRec()
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "jv")
    }

    /* ---------- ligação com o PC (ntfy.sh) ---------- */
    private fun startSub() {
        val topic = Core.pair(this)
        if (topic == subTopic && subRun) return
        subRun = false
        subTopic = topic
        if (topic.isBlank()) return
        subRun = true
        subThread = Thread {
            var delay = 2000L
            while (subRun && subTopic == topic) {
                try {
                    val cn = URL("https://ntfy.sh/$topic/json").openConnection() as HttpURLConnection
                    cn.connectTimeout = 15000
                    cn.readTimeout = 120000
                    val br = BufferedReader(InputStreamReader(cn.inputStream, Charsets.UTF_8))
                    delay = 2000L
                    while (subRun && subTopic == topic) {
                        val line = br.readLine() ?: break
                        val o = JSONObject(line)
                        if (o.optString("event") == "message") {
                            val msg = JSONObject(o.optString("message"))
                            h.post {
                                val reply = try { Core.handleRemote(this, msg) } catch (e: Exception) { null }
                                if (!reply.isNullOrBlank()) speak(reply)
                            }
                        }
                    }
                    cn.disconnect()
                } catch (e: Exception) {
                }
                try { Thread.sleep(delay) } catch (e: InterruptedException) { }
                if (delay < 30000L) delay *= 2
            }
        }
        subThread?.start()
    }

    fun stopAll() {
        subRun = false
        stopRec()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        subRun = false
        stopRec()
        try { sr?.destroy() } catch (e: Exception) { }
        sr = null
        tts?.shutdown()
        instance = null
        super.onDestroy()
    }
}
