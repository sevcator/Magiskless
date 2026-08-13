package com.topjohnwu.magisk.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.magisk.ui.theme.Theme
import com.topjohnwu.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShellActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.apply(this)
        super.onCreate(savedInstanceState)
        if (!Shortcuts.consumeShellShortcut(this, intent)) {
            finish()
            return
        }
        setContentView(R.layout.activity_shell)
        output = findViewById(R.id.shell_output)
        input = findViewById(R.id.shell_input)
        scroll = findViewById(R.id.shell_scroll)
        val run = findViewById<Button>(R.id.shell_run)
        output.text = getString(CoreR.string.shell_ready) + "\n$ "
        run.setOnClickListener { runInput() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                runInput()
                true
            } else {
                false
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Shortcuts.consumeShellShortcut(this, intent)) setIntent(intent)
    }

    private fun runInput() {
        val command = input.text.toString().trim()
        if (command.isEmpty()) return
        input.text.clear()
        append("$command\n")
        when (command.lowercase()) {
            "reisenless" -> startActivity(Intent(this, MainActivity::class.java))
            "restore" -> {
                Shortcuts.restoreLauncher(this)
                append("launcher restored\n")
            }
            "clear" -> output.text = "$ "
            "exit" -> finish()
            else -> lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { Shell.cmd(command).exec() }
                if (result.out.isNotEmpty()) append(result.out.joinToString("\n") + "\n")
                if (result.err.isNotEmpty()) append(result.err.joinToString("\n") + "\n")
                append("[${result.code}]\n$ ")
            }
        }
    }

    private fun append(text: String) {
        output.append(text)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
