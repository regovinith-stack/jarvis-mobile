package com.jarvis.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Listens for "Jarvis" using Android's on-device recognizer (EXTRA_PREFER_OFFLINE), then sends
 * what you say next to the assistant, runs the resulting actions and speaks the reply.
 * You can say it in one go: "Jarvis, open YouTube".
 *
 * Offline recognition needs the language pack downloaded:
 * Settings > System > Languages & input > On-device speech recognition.
 * Without it, Android quietly falls back to online recognition.
 */
class WakeWordService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var awake = false
    private var processing = false
    private var listenJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "jarvis_listening"
        const val NOTIF_ID = 1
        const val WAKE_WORD = "jarvis"
        const val ACTION_STOP = "com.jarvis.agent.STOP"
        private const val IDLE_TEXT = "Listening for \"Jarvis\"..."
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val n = buildNotification(IDLE_TEXT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            JarvisEventBus.emit(JarvisEvent.Error("Speech recognition isn't available on this device."))
            stopSelf()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        scheduleListen(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun scheduleListen(delayMs: Long = 300) {
        listenJob?.cancel()
        listenJob = scope.launch {
            delay(delayMs)
            if (!processing) listenOnce()
        }
    }

    private fun listenOnce() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
        }
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()

            if (awake) {
                awake = false
                if (text.isBlank()) scheduleListen() else process(text)
                return
            }

            val i = text.indexOf(WAKE_WORD, ignoreCase = true)
            if (i < 0) {
                scheduleListen()
                return
            }
            val rest = text.substring(i + WAKE_WORD.length).trim(' ', ',', '.', '!', '?')
            if (rest.isNotBlank()) {
                process(rest)
            } else {
                awake = true
                updateNotification("Yes? Listening...")
                JarvisEventBus.emit(JarvisEvent.Status("Listening..."))
                speak("Yes?") { scheduleListen(0) }
            }
        }

        override fun onError(error: Int) {
            awake = false
            if (!processing) scheduleListen()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun process(text: String) {
        processing = true
        listenJob?.cancel()
        JarvisEventBus.emit(JarvisEvent.UserSaid(text))
        JarvisEventBus.emit(JarvisEvent.Status("Thinking..."))
        updateNotification("Working on: $text")

        scope.launch {
            val reply = try {
                Assistant.handle(applicationContext, text)
            } catch (e: Exception) {
                JarvisEventBus.emit(JarvisEvent.Error(e.message ?: "Something went wrong."))
                "Sorry, something went wrong."
            }
            JarvisEventBus.emit(JarvisEvent.Reply(reply))
            speak(reply) {
                processing = false
                updateNotification(IDLE_TEXT)
                JarvisEventBus.emit(JarvisEvent.Status(IDLE_TEXT))
                scheduleListen()
            }
        }
    }

    private fun speak(text: String, then: () -> Unit) {
        val engine = tts
        if (engine == null || !ttsReady || text.isBlank()) {
            then()
            return
        }
        val id = "u${System.nanoTime()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                scope.launch { then() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                scope.launch { then() }
            }
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Jarvis listening", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
