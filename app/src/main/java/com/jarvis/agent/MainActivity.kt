package com.jarvis.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var chatList: RecyclerView
    private lateinit var adapter: ChatAdapter

    private val idleText = "Say \"Jarvis\", or type below"

    private val requestPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) startJarvisService()
            else statusText.text = "Microphone permission is needed for voice. You can still type."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        chatList = findViewById(R.id.chatList)
        val input = findViewById<EditText>(R.id.manualInput)
        val send = findViewById<Button>(R.id.sendButton)

        adapter = ChatAdapter()
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = adapter
        statusText.text = idleText

        val submit = {
            val task = input.text.toString().trim()
            if (task.isNotEmpty()) {
                input.text.clear()
                runTyped(task)
            }
        }
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }

        findViewById<Button>(R.id.apiKeyButton).setOnClickListener { showApiKeyDialog() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.overlayButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.memoryButton).setOnClickListener { showMemoryDialog() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP))
            statusText.text = "Voice listening stopped. You can still type."
        }

        observeEvents()
        ensurePermissionsThenStart()
    }

    private fun runTyped(task: String) {
        post(ChatMessage(task, true))
        statusText.text = "Thinking..."
        lifecycleScope.launch {
            try {
                post(ChatMessage(Assistant.handle(applicationContext, task), false))
            } catch (e: Exception) {
                post(ChatMessage("Error: ${e.message}", false))
            }
            statusText.text = idleText
        }
    }

    private fun post(m: ChatMessage) {
        adapter.add(m)
        chatList.scrollToPosition(adapter.itemCount - 1)
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                JarvisEventBus.events.collect { e ->
                    when (e) {
                        is JarvisEvent.Status -> statusText.text = e.text
                        is JarvisEvent.UserSaid -> post(ChatMessage(e.text, true))
                        is JarvisEvent.Reply -> post(ChatMessage(e.text, false))
                        is JarvisEvent.Error -> statusText.text = "Error: ${e.message}"
                    }
                }
            }
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf<String>()
        if (!hasMic()) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed.add(Manifest.permission.POST_NOTIFICATIONS)

        if (needed.isEmpty()) startJarvisService() else requestPerms.launch(needed.toTypedArray())
    }

    private fun startJarvisService() {
        val intent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun showApiKeyDialog() {
        val field = EditText(this).apply {
            hint = "Anthropic API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("API key")
            .setMessage("Stored only on this phone. Used to talk to the Claude API.")
            .setView(field)
            .setPositiveButton("Save") { _, _ -> AppSettings.setApiKey(this, field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMemoryDialog() {
        val facts = Memory(this).facts()
        val body = if (facts.isEmpty()) "Nothing remembered yet." else facts.joinToString("\n") { "• $it" }
        AlertDialog.Builder(this)
            .setTitle("What Jarvis remembers")
            .setMessage(body)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear all") { _, _ -> Memory(this).clearAll() }
            .show()
    }
}
