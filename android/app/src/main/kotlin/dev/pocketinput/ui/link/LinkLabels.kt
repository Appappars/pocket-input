package dev.pocketinput.ui.link

import androidx.annotation.StringRes
import dev.pocketinput.R
import dev.pocketinput.hid.LinkCause
import dev.pocketinput.hid.LinkState

@StringRes
internal fun LinkState.label(): Int = when (this) {
    LinkState.STOPPED -> R.string.link_stopped
    LinkState.NEEDS_PERMISSION -> R.string.link_permission_required
    LinkState.BLUETOOTH_OFF -> R.string.link_bluetooth_off
    LinkState.NO_ADAPTER -> R.string.link_no_adapter
    LinkState.INIT -> R.string.link_registering
    LinkState.NO_PROFILE -> R.string.link_no_profile
    LinkState.REGISTERED -> R.string.link_waiting_for_pc
    LinkState.CONNECTING -> R.string.link_connecting_to_pc
    LinkState.RECOVERING -> R.string.link_recovering
    LinkState.FAILED -> R.string.link_failed
    LinkState.CONNECTED -> R.string.link_connected
}

@StringRes
internal fun LinkCause.label(): Int = when (this) {
    LinkCause.PROFILE_UNAVAILABLE -> R.string.link_cause_profile_unavailable
    LinkCause.PROFILE_TIMEOUT -> R.string.link_cause_profile_timeout
    LinkCause.PROFILE_LOST -> R.string.link_cause_profile_lost
    LinkCause.REGISTRATION_FAILED -> R.string.link_cause_registration_failed
    LinkCause.REGISTRATION_LOST -> R.string.link_cause_registration_lost
    LinkCause.HOST_DISCONNECTED -> R.string.link_cause_disconnected
    LinkCause.HOST_UNAVAILABLE -> R.string.link_cause_host_unavailable
    LinkCause.SERVICE_FAILED -> R.string.link_cause_service_failed
}
