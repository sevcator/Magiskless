package com.topjohnwu.magisk.ui.webui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.superuser.Shell
import org.json.JSONObject
import java.io.File

/** Small compatibility bridge for the KernelSU/Magisk WebUI API. */
internal class WebViewInterface(
    private val context: Context,
    private val webView: WebView,
    private val moduleId: String,
    private val moduleName: String,
) {
    @JavascriptInterface
    fun exec(command: String): String = runCommand(command).output

    @JavascriptInterface
    fun exec(command: String, callback: String) {
        exec(command, null, callback)
    }

    @JavascriptInterface
    fun exec(command: String, options: String?, callback: String) {
        val result = runCommand(withOptions(command, options))
        postCallback(callback, result)
    }

    @JavascriptInterface
    fun spawn(command: String, args: String, options: String?, callback: String) {
        val commandLine = buildString {
            append(withOptions(command, options))
            if (args.isNotBlank()) {
                val values = runCatching { org.json.JSONArray(args) }.getOrNull()
                if (values != null) {
                    for (index in 0 until values.length()) {
                        append(' ').append(values.optString(index))
                    }
                }
            }
        }
        postCallback(callback, runCommand(commandLine))
    }

    @JavascriptInterface
    fun toast(message: String) {
        webView.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
        val activity = context as? Activity ?: return
        Handler(Looper.getMainLooper()).post {
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            if (enable) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    @JavascriptInterface
    fun moduleInfo(): String = JSONObject().apply {
        put("id", moduleId)
        put("name", moduleName)
        put("moduleDir", File(Const.MODULE_PATH, moduleId).path)
    }.toString()

    private fun withOptions(command: String, options: String?): String {
        val opts = options?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return command
        return buildString {
            opts.optString("cwd").takeIf { it.isNotBlank() }?.let { append("cd ").append(it).append(';') }
            opts.optJSONObject("env")?.keys()?.forEach { key ->
                append("export ").append(key).append('=').append(opts.getJSONObject("env").getString(key)).append(';')
            }
            append(command)
        }
    }

    private fun runCommand(command: String): CommandResult {
        val result = Shell.cmd(command).exec()
        return CommandResult(
            code = result.code,
            output = result.out.joinToString("\n"),
            error = result.err.joinToString("\n"),
        )
    }

    private fun postCallback(callback: String, result: CommandResult) {
        val js = """
            (() => {
                try {
                    const cb = $callback;
                    if (cb && cb.stdout) cb.stdout.emit('data', ${JSONObject.quote(result.output)});
                    if (cb && cb.stderr && ${!result.error.isNullOrEmpty()}) cb.stderr.emit('data', ${JSONObject.quote(result.error)});
                    if (cb) cb.emit('exit', ${result.code});
                } catch (e) { console.error(e); }
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private data class CommandResult(val code: Int, val output: String, val error: String)
}
