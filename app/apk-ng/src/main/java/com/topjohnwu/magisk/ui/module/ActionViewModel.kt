package com.topjohnwu.magisk.ui.module

import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.terminal.TerminalEmulator
import com.topjohnwu.magisk.terminal.runSuCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActionViewModel : BaseViewModel() {

    var actionId: String = ""
    private val emulatorReady = CompletableDeferred<TerminalEmulator>()

    fun onEmulatorCreated(emu: TerminalEmulator) {
        emulatorReady.complete(emu)
    }

    fun startRunAction() {
        viewModelScope.launch {
            val emu = emulatorReady.await()

            withContext(Dispatchers.IO) {
                runSuCommand(
                    emu,
                    "cd ${Const.MODULE_PATH}/$actionId && sh ./action.sh"
                )
            }
        }
    }

}
