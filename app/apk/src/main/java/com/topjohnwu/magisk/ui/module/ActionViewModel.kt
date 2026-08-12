package com.topjohnwu.magisk.ui.module

import androidx.databinding.ObservableArrayList
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.ktx.synchronized
import com.topjohnwu.magisk.ui.flash.ConsoleItem
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class ActionViewModel : BaseViewModel() {

    enum class State {
        RUNNING, SUCCESS, FAILED
    }

    private val _state = MutableLiveData(State.RUNNING)
    val state: LiveData<State> get() = _state

    val items = ObservableArrayList<ConsoleItem>()
    lateinit var args: ActionFragmentArgs

    private val logItems = mutableListOf<String>().synchronized()
    private val outItems = object : CallbackList<String>() {
        override fun onAddElement(e: String?) {
            e ?: return
            items.add(ConsoleItem(e))
            logItems.add(e)
        }
    }

    fun startRunAction() = viewModelScope.launch {
        onResult(withContext(Dispatchers.IO) {
            try {
                Shell.cmd("run_action \'${args.id}\'")
                    .to(outItems, logItems)
                    .exec().isSuccess
            } catch (e: IOException) {
                false
            }
        })
    }

    private fun onResult(success: Boolean) {
        _state.value = if (success) State.SUCCESS else State.FAILED
    }

}
