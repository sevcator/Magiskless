package com.topjohnwu.magisk.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.magisk.view.Shortcuts
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShellActivity : ComponentActivity() {

    private var output by mutableStateOf("")
    private var command by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Shortcuts.consumeShellShortcut(this, intent)) {
            finish()
            return
        }
        output = getString(CoreR.string.shell_ready) + "\n$ "
        setContent {
            MagiskTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101010))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = output,
                        color = Color(0xFFF4A6C1),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(getString(CoreR.string.shell_hint)) },
                        )
                        Button(onClick = ::runInput) {
                            Text(getString(CoreR.string.shell_run))
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Shortcuts.consumeShellShortcut(this, intent)) setIntent(intent)
    }

    private fun runInput() {
        val current = command.trim()
        if (current.isEmpty()) return
        command = ""
        output += "$current\n"
        when (current.lowercase()) {
            "reisenless" -> startActivity(Intent(this, MainActivity::class.java))
            "restore" -> {
                Shortcuts.restoreLauncher(this)
                output += "launcher restored\n$ "
            }
            "clear" -> output = "$ "
            "exit" -> finish()
            else -> lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { Shell.cmd(current).exec() }
                if (result.out.isNotEmpty()) output += result.out.joinToString("\n") + "\n"
                if (result.err.isNotEmpty()) output += result.err.joinToString("\n") + "\n"
                output += "[${result.code}]\n$ "
            }
        }
    }
}
