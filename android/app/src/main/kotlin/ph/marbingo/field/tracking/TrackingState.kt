package ph.marbingo.field.tracking

/** What the OS currently lets us do. [background] and [notifications] are reported true on versions that don't need them. */
data class PermissionState(
    val fine: Boolean = false,
    val background: Boolean = false,
    val notifications: Boolean = false,
    val batteryExempt: Boolean = false,
    val gpsEnabled: Boolean = true,
)

data class TrackingState(
    val schedule: TrackingSchedule = TrackingSchedule.DEFAULT,
    val now: Long = System.currentTimeMillis(),
    val consentGiven: Boolean = false,
    val permissions: PermissionState = PermissionState(),
    val serviceRunning: Boolean = false,
    val pendingPoints: Int = 0,
    val lastFixAt: Long? = null,
    val lastUploadAt: Long? = null,
    val lastError: String? = null,
) {
    val inHoursNow: Boolean get() = schedule.isInHours(now)

    /** Steps 1–3 of the setup: consent, precise location and "all the time". */
    val setupComplete: Boolean get() = consentGiven && permissions.fine && permissions.background

    /** Inside work hours but something stops us sharing: show the red banner. */
    val needsAttention: Boolean get() = inHoursNow && (!setupComplete || !permissions.gpsEnabled)

    /** Everything is in place and the service is expected to be (or about to be) running. */
    val sharing: Boolean get() = inHoursNow && !needsAttention && serviceRunning

    companion object {
        /** A complete, off-duty-agnostic state for fakes and previews. */
        val Ready = TrackingState(
            consentGiven = true,
            permissions = PermissionState(fine = true, background = true, notifications = true, batteryExempt = true),
        )
    }
}
