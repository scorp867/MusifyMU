package com.musify.mu.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

class CommandController(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun listen(): Flow<String> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            close()
            return@callbackFlow
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.let { trySend(it.lowercase(Locale.getDefault())) }
            }
            override fun onReadyForSpeech(params: Bundle) {}
            override fun onError(error: Int) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle) {}
            override fun onPartialResults(partialResults: Bundle) {}
            override fun onRmsChanged(rmsdB: Float) {}
        }
        recognizer?.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        recognizer?.startListening(intent)

        awaitClose {
            recognizer?.stopListening()
            recognizer?.destroy()
        }
    }

    fun interpretCommand(text: String): Command? {
        return when {
            listOf("play", "resume").any { it in text } -> Command.PLAY
            listOf("pause", "stop").any { it in text } -> Command.PAUSE
            listOf("next", "skip").any { it in text } -> Command.NEXT
            listOf("previous", "back").any { it in text } -> Command.PREV
            "shuffle" in text && "on" in text -> Command.SHUFFLE_ON
            "shuffle" in text && "off" in text -> Command.SHUFFLE_OFF
            "repeat" in text && "one" in text -> Command.REPEAT_ONE
            "repeat" in text && "all" in text -> Command.REPEAT_ALL
            "repeat" in text && "off" in text -> Command.REPEAT_OFF
            else -> null
        }
    }

    enum class Command {
        PLAY, PAUSE, NEXT, PREV,
        SHUFFLE_ON, SHUFFLE_OFF,
        REPEAT_ONE, REPEAT_ALL, REPEAT_OFF
    }
}
