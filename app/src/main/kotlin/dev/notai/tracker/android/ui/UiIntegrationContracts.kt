package dev.notai.tracker.android.ui

import kotlinx.coroutines.flow.StateFlow

/** Implemented by the shared ViewModel. Preview and apply must both retain the core's stale guards. */
interface BatchDeletionUiHost {
    suspend fun previewPickedDeletion(): Result<PickedDeletionReview>
}

/** A owns the captured core preview. Apply reports success only after commit and publishes the existing Undo notice. */
data class PickedDeletionReview(
    val sessionIds: Set<String>,
    val consequences: List<String>,
    val apply: suspend () -> Result<Unit>,
)

/** Device-local preference only; the report zone in Dataset/portable backups remains an ordinary IANA zone. */
interface ReportingZoneUiHost {
    val reportZoneAutomatic: StateFlow<Boolean>
    fun useAutomaticReportZone()
    // Existing TrackerViewModel.setReportZone handles manual selection and must also leave AUTO mode.
}

/** Resource and labels come from the platform controller; compatible keys share the same actual image. */
data class LauncherIconUiOption(val key: String, val title: String, val previewDrawable: Int,
                               val compatibleKeys: Set<String> = setOf(key))
data class LauncherIconsUiState(val selectedKey: String?, val options: List<LauncherIconUiOption>, val changing: Boolean = false,
                                val problem: String? = null)

/** The shared ViewModel forwards C's controller state. The controller owns persistence and alias switching. */
interface LauncherIconsUiHost {
    val launcherIcons: StateFlow<LauncherIconsUiState>
    fun chooseLauncherIcon(key: String)
}
