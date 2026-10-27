package ph.marbingo.field.update

import ph.marbingo.core.model.AppUpdateDto

/** A published build newer than the one running. [url] is as the server sent it (relative to the API base URL). */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val minVersionCode: Int,
) {
    /** True when this device's build is older than the minimum the server still supports. */
    fun isRequiredFor(currentVersionCode: Int) = minVersionCode > currentVersionCode
}

fun AppUpdateDto.toInfo() = UpdateInfo(versionCode, versionName, notes, url, sha256.lowercase(), size, minVersionCode)

sealed interface UpdateState {
    /** Nothing checked yet (or an automatic check failed quietly). */
    data object Idle : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo, val required: Boolean) : UpdateState
    /** [progress] is 0..1. */
    data class Downloading(val progress: Float) : UpdateState
    /** Downloaded and verified; the system installer has been (or is about to be) started. */
    data object ReadyToInstall : UpdateState
    /** The user must allow installs from this app in system settings first. */
    data object NeedsPermission : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Whether a published build means an update for this device. */
fun decideUpdate(info: UpdateInfo?, currentVersionCode: Int): UpdateState = when {
    info == null || info.versionCode <= currentVersionCode -> UpdateState.UpToDate
    else -> UpdateState.Available(info, required = info.isRequiredFor(currentVersionCode))
}
