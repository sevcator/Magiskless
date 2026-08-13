package com.topjohnwu.magisk.ui.superuser

import android.graphics.drawable.Drawable
import androidx.databinding.Bindable
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.databinding.DiffItem
import com.topjohnwu.magisk.databinding.ItemWrapper
import com.topjohnwu.magisk.databinding.ObservableRvItem
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.core.R as CoreR

class PolicyRvItem(
    private val viewModel: SuperuserViewModel,
    override val item: SuPolicy,
    val packageName: String,
    private val isSharedUid: Boolean,
    val icon: Drawable,
    val appName: String
) : ObservableRvItem(), DiffItem<PolicyRvItem>, ItemWrapper<SuPolicy> {

    override val layoutRes = R.layout.item_policy_md2

    val title get() = if (isSharedUid) "[shareduid] ${appName.lowercase()}" else appName.lowercase()

    private inline fun <reified T> setImpl(new: T, old: T, setter: (T) -> Unit) {
        if (old != new) {
            setter(new)
        }
    }

    @get:Bindable
    var isExpanded = false
        set(value) = set(value, field, { field = it }, BR.expanded)

    val showSlider = item.policy == SuPolicy.RESTRICT

    @get:Bindable
    var isEnabled
        get() = item.policy >= SuPolicy.ALLOW
        set(value) = setImpl(value, isEnabled) {
            notifyPropertyChanged(BR.enabled)
            viewModel.updatePolicy(this, if (it) SuPolicy.ALLOW else SuPolicy.DENY)
        }

    @get:Bindable
    var sliderValue
        get() = item.policy
        set(value) = setImpl(value, sliderValue) {
            notifyPropertyChanged(BR.sliderValue)
            notifyPropertyChanged(BR.enabled)
            viewModel.updatePolicy(this, it)
        }

    val sliderValueToPolicyString: (Float) -> Int = { value ->
        when (value.toInt()) {
            1 -> CoreR.string.deny
            2 -> CoreR.string.restrict
            3 -> CoreR.string.grant
            else -> CoreR.string.deny
        }
    }

    @get:Bindable
    var shouldNotify
        get() = item.notification
        private set(value) = setImpl(value, shouldNotify) {
            item.notification = it
            viewModel.updateNotify(this)
        }

    fun toggleExpand() {
        isExpanded = !isExpanded
    }

    fun toggleNotify() {
        shouldNotify = !shouldNotify
    }

    fun revoke() {
        viewModel.deletePressed(this)
    }

    override fun itemSameAs(other: PolicyRvItem) = packageName == other.packageName

    override fun contentSameAs(other: PolicyRvItem) = item.policy == other.item.policy

}
