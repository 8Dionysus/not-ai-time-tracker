package dev.notai.tracker.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/** Presentation/policy inputs only. The backend owns the stable channel ID and its lifecycle.
 * Supply application-owned rendering and existing Android string resources, never an Activity.
 * Importance is explicit for the initial channel; an existing user channel is never reset. */
data class ScheduleNoticeConfiguration(val factory: ScheduleNoticeFactory,val channelNameRes: Int,
    val importance: Int,val channelDescriptionRes: Int? = null) {
    init {
        require(channelNameRes != 0 && channelDescriptionRes != 0)
        require(importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_MAX)
    }
}
enum class ScheduleNoticeBindingState { UNCONFIGURED, BOUND, UNAVAILABLE }

/** Process-local registration is rebuilt by TrackerApplication before runtime/receivers. No DB,
 * permission prompt, notification post, alarm or cleanup happens at application startup. */
object ScheduleNoticeBinding {
    internal class Registration(val factory: ScheduleNoticeFactory?)
    private val lock = Any()
    @Volatile private var registration = Registration(null)
    private var configuration: ScheduleNoticeConfiguration? = null
    @Volatile var state = ScheduleNoticeBindingState.UNCONFIGURED
        private set
    internal val factory: ScheduleNoticeFactory? get() = registration.factory
    internal fun capture(): Registration = registration
    internal fun publishIfCurrent(expected: Registration,post: () -> Boolean): Boolean = synchronized(lock) {
        registration === expected && expected.factory != null && post()
    }
    /** Compatibility for explicit process-local bindings. This alone cannot survive process death. */
    fun install(value: ScheduleNoticeFactory?) {
        synchronized(lock) {
            configuration = null;registration = Registration(value)
            state = if(value == null) ScheduleNoticeBindingState.UNCONFIGURED else ScheduleNoticeBindingState.BOUND
        }
        TrackerRuntime.refreshExistingSchedule()
    }
    internal fun initialize(application: Application,provider: () -> ScheduleNoticeConfiguration?): ScheduleNoticeBindingState {
        synchronized(lock) {
            configuration = null;registration = Registration(null);state = ScheduleNoticeBindingState.UNCONFIGURED
            try {
                val value = provider()
                if(value != null) {
                    channel(application,value) // Channel is ready before publishing the renderer.
                    configuration = value;registration = Registration(value.factory);state = ScheduleNoticeBindingState.BOUND
                }
            } catch(_: Exception) {
                state = ScheduleNoticeBindingState.UNAVAILABLE
                android.util.Log.w("ScheduleDelivery","Application reminder binding unavailable; no reminder attempt consumed")
            }
        }
        TrackerRuntime.refreshExistingSchedule() // Never initializes a missing runtime/store.
        return state
    }
    internal fun refreshChannelMetadata(application: Application) {
        synchronized(lock) {
            configuration?.let { value -> try { channel(application,value) } catch(_: Exception) {
                android.util.Log.w("ScheduleDelivery","Reminder channel metadata refresh unavailable; existing user policy retained")
            } }
        }
    }
    private fun channel(application: Application,value: ScheduleNoticeConfiguration) {
        val manager = checkNotNull(application.getSystemService(NotificationManager::class.java))
        val name = application.getString(value.channelNameRes).also { require(it.isNotBlank()) }
        val description = value.channelDescriptionRes?.let(application::getString)
        val channel = manager.getNotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL)
            ?: NotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL,name,value.importance)
        // Reuse all actual importance/sound/vibration/user policy of an existing channel.
        channel.name = name;channel.description = description
        manager.createNotificationChannel(channel)
        check(manager.getNotificationChannel(AndroidScheduleDeliveryPlatform.CHANNEL) != null)
    }
}
