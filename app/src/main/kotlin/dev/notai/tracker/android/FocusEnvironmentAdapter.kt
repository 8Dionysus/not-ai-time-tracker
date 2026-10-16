package dev.notai.tracker.android

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import android.service.notification.Condition
import dev.notai.tracker.*
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.*

/** Opus supplies a real rule-configuration Activity and its copy. Missing configuration is
 * explicit; this adapter does not substitute MainActivity as a pretend DND settings screen. */
data class FocusRuleConfiguration(val activity: ComponentName,val name: String,val summary: String)
object FocusEnvironmentBinding {
    @Volatile var rule: FocusRuleConfiguration? = null
    /** Must resolve an explicitly selected LOCAL regular file. No URI/HTTP/account resolver. */
    @Volatile var localFile: ((String)->File?)? = null
    /** Set from the host lifecycle. False is the conservative default; no foreground service. */
    @Volatile var foreground: Boolean = false
}
private val focusEnvironmentGates = mutableMapOf<String,Any>()
class AndroidFocusEnvironmentStore(context: Context): FocusEnvironmentStore {
    private val file = File(context.applicationContext.noBackupFilesDir,"focus-environment.json")
    private val metadata = PrivateFileMaintenance.metadata(file,64*1024)
    private val gate = synchronized(focusEnvironmentGates) { focusEnvironmentGates.getOrPut(file.absolutePath) { Any() } }
    override fun <T> locked(block: ()->T): T = synchronized(gate) { block() }
    override fun read() = metadata.read()?.let { codec.decodeFromString<FocusEnvironmentControl>(it) } ?: FocusEnvironmentControl()
    override fun writeDurably(value: FocusEnvironmentControl) = metadata.writeDurably(codec.encodeToString(value))
}
class AndroidFocusDistractions(context: Context): FocusDistractionPort {
    private val context = context.applicationContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    companion object {
        const val ACTION = "dev.notai.tracker.FOCUS_ENVIRONMENT_EXPIRED"
        const val REQUEST = "focusEnvironmentRequest"
    }
    override fun availability(): FocusEffectStatus {
        if(Build.VERSION.SDK_INT < 29) return FocusEffectStatus.UNSUPPORTED
        if(!manager.isNotificationPolicyAccessGranted) return FocusEffectStatus.ACCESS_REQUIRED
        val config = FocusEnvironmentBinding.rule ?: return FocusEffectStatus.CONFIGURATION_REQUIRED
        if(config.activity.packageName != context.packageName || config.name.isBlank() || config.summary.isBlank()) return FocusEffectStatus.CONFIGURATION_REQUIRED
        // Required API29 configuration Activity must exist; launching it belongs to the UI.
        return try { context.packageManager.getActivityInfo(config.activity,0);FocusEffectStatus.READY }
            catch(_: android.content.pm.PackageManager.NameNotFoundException) { FocusEffectStatus.CONFIGURATION_REQUIRED }
    }
    private fun condition(id: String) = Uri.Builder().scheme(Condition.SCHEME).authority(context.packageName)
        .appendPath("focus-environment").appendPath(id).build()
    private fun fingerprint(rule: AutomaticZenRule): String {
        val parcel = Parcel.obtain()
        return try {
            rule.writeToParcel(parcel,0)
            MessageDigest.getInstance("SHA-256").digest(parcel.marshall()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
        } finally { parcel.recycle() }
    }
    override fun prepare(request: FocusEnvironmentRequest): FocusDndLease? {
        if(availability() != FocusEffectStatus.READY || Build.VERSION.SDK_INT < 29) return null
        val config = FocusEnvironmentBinding.rule ?: return null
        val uri = condition(request.id)
        val existing = manager.automaticZenRules.filterValues { it.conditionId == uri && it.configurationActivity == config.activity && it.owner == null }
        // An orphan or user-adopted rule is not silently reset or reactivated.
        if(existing.isNotEmpty()) return null
        val id = manager.addAutomaticZenRule(AutomaticZenRule(config.name,null,config.activity,uri,null,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,true)) ?: return null
        manager.setAutomaticZenRuleState(id,Condition(uri,config.summary,Condition.STATE_FALSE))
        val rule = manager.getAutomaticZenRule(id) ?: return null
        // Enabled is configuration; STATE_FALSE keeps the condition INACTIVE until activation.
        return FocusDndLease(request.id,id,uri.toString(),fingerprint(rule))
    }
    private fun owned(lease: FocusDndLease): AutomaticZenRule? {
        if(Build.VERSION.SDK_INT < 29) return null
        val rule = manager.getAutomaticZenRule(lease.ruleId) ?: return null
        return rule.takeIf { it.conditionId.toString() == lease.conditionId && it.conditionId == condition(lease.requestId) &&
            it.owner == null && it.configurationActivity?.packageName == context.packageName }
    }
    override fun activate(lease: FocusDndLease): FocusEffectStatus {
        if(Build.VERSION.SDK_INT < 29) return FocusEffectStatus.UNSUPPORTED
        if(!manager.isNotificationPolicyAccessGranted) return FocusEffectStatus.ACCESS_REQUIRED
        val rule = owned(lease) ?: return FocusEffectStatus.USER_CHANGED_RULE
        if(fingerprint(rule) != lease.fingerprint) return FocusEffectStatus.USER_CHANGED_RULE
        val summary = FocusEnvironmentBinding.rule?.summary ?: return FocusEffectStatus.CONFIGURATION_REQUIRED
        manager.setAutomaticZenRuleState(lease.ruleId,Condition(Uri.parse(lease.conditionId),summary,Condition.STATE_TRUE))
        return FocusEffectStatus.APPLY_ACCEPTED // accepted contribution, never proof of global DND.
    }
    override fun release(lease: FocusDndLease): FocusEffectStatus {
        if(Build.VERSION.SDK_INT < 29) return FocusEffectStatus.UNSUPPORTED
        if(!manager.isNotificationPolicyAccessGranted) return FocusEffectStatus.ACCESS_REQUIRED
        val rule = manager.getAutomaticZenRule(lease.ruleId) ?: return FocusEffectStatus.RELEASED
        if(owned(lease) == null || fingerprint(rule) != lease.fingerprint) return FocusEffectStatus.USER_CHANGED_RULE
        manager.setAutomaticZenRuleState(lease.ruleId,Condition(Uri.parse(lease.conditionId),FocusEnvironmentBinding.rule?.summary ?: "Focus",Condition.STATE_FALSE))
        return if(manager.removeAutomaticZenRule(lease.ruleId)) FocusEffectStatus.RELEASED else FocusEffectStatus.FAILED
    }
    private fun alarmIntent() = Intent(context,FocusEnvironmentExpiryReceiver::class.java).setAction(ACTION)
        .setData(Uri.parse("notai://focus-environment/expiry"))
    override fun scheduleRelease(request: FocusEnvironmentRequest) {
        val pending = PendingIntent.getBroadcast(context,0,alarmIntent().putExtra(REQUEST,request.id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,request.expiresElapsed,pending)
    }
    override fun cancelRelease() {
        val pending = PendingIntent.getBroadcast(context,0,alarmIntent(),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
    }
}

/** App-owned local playback only. It does not change stream volume, ringer, remote sessions,
 * media buttons, external queues or another player's position. Loss never auto-resumes. */
class AndroidFocusMusic(context: Context): FocusMusicPort {
    private val context = context.applicationContext
    private val manager get() = context.getSystemService(AudioManager::class.java)
    private var owner: String? = null
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var state = FocusEffectStatus.OFF
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    @Synchronized override fun availability(localId: String?): FocusEffectStatus {
        if(!FocusEnvironmentBinding.foreground) return FocusEffectStatus.FOREGROUND_REQUIRED
        if(localId == null || FocusEnvironmentBinding.localFile?.invoke(localId)?.isFile != true) return FocusEffectStatus.SOURCE_REQUIRED
        if(manager.isMusicActive && player?.isPlaying != true) return FocusEffectStatus.OTHER_AUDIO_ACTIVE
        return FocusEffectStatus.READY
    }
    @Synchronized override fun prepare(requestId: String,localId: String): PreparedFocusMusic? {
        if(availability(localId) != FocusEffectStatus.READY) return null
        owner?.let { stop(it) }
        val file = FocusEnvironmentBinding.localFile?.invoke(localId) ?: return null
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attributes)
            FileInputStream(file).use { p.setDataSource(it.fd) }
            p.isLooping = true;p.prepare()
        } catch(_: Exception) { p.release();state = FocusEffectStatus.FAILED;return null }
        owner = requestId;player = p;state = FocusEffectStatus.READY
        p.setOnErrorListener { _,_,_ -> synchronized(this) { if(owner == requestId) { stop(requestId);state = FocusEffectStatus.FAILED } };true }
        return PreparedFocusMusic { startPrepared(requestId,p) }
    }
    @Synchronized private fun startPrepared(requestId: String,p: MediaPlayer): FocusEffectStatus {
        if(owner != requestId || player !== p) return FocusEffectStatus.INTERRUPTED
        val ready = when {
            !FocusEnvironmentBinding.foreground -> FocusEffectStatus.FOREGROUND_REQUIRED
            manager.isMusicActive && !p.isPlaying -> FocusEffectStatus.OTHER_AUDIO_ACTIVE
            else -> FocusEffectStatus.READY
        }
        if(ready != FocusEffectStatus.READY) { stop(requestId);state = ready;return ready }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if(change != AudioManager.AUDIOFOCUS_GAIN) synchronized(this) {
                    if(owner == requestId) { stop(requestId);state = FocusEffectStatus.INTERRUPTED }
                }
            },Handler(Looper.getMainLooper())).build()
        focusRequest = request
        if(manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop(requestId);state = FocusEffectStatus.AUDIO_FOCUS_DENIED;return state
        }
        return try { p.start();state = FocusEffectStatus.ACTIVE;state }
            catch(_: RuntimeException) { stop(requestId);state = FocusEffectStatus.FAILED;state }
    }
    @Synchronized override fun status(requestId: String): FocusEffectStatus {
        if(owner == requestId && !FocusEnvironmentBinding.foreground) { stop(requestId);state = FocusEffectStatus.FOREGROUND_REQUIRED }
        return if(owner == null || owner == requestId) state else FocusEffectStatus.OFF
    }
    @Synchronized fun foregroundChanged(value: Boolean) {
        if(!value) owner?.let { stop(it);state = FocusEffectStatus.FOREGROUND_REQUIRED }
    }
    @Synchronized override fun stop(requestId: String): FocusEffectStatus {
        if(owner != requestId) return FocusEffectStatus.OFF
        player?.release();player = null
        focusRequest?.let { manager.abandonAudioFocusRequest(it) };focusRequest = null;owner = null
        state = FocusEffectStatus.RELEASED;return state
    }
}
private val focusMusicPorts = mutableMapOf<String,AndroidFocusMusic>()
private fun focusMusic(context: Context) = synchronized(focusMusicPorts) {
    focusMusicPorts.getOrPut(context.applicationContext.noBackupFilesDir.absolutePath) { AndroidFocusMusic(context.applicationContext) }
}
class AndroidFocusEnvironment(context: Context,tracker: Tracker) {
    private val application = context.applicationContext
    private val music = focusMusic(application)
    val controller = FocusEnvironment(tracker,AndroidFocusEnvironmentStore(application),AndroidFocusDistractions(application),music)
    fun synchronize() = controller.synchronize()
    /** Main-safe lifecycle hint: no monitor, media preparation, file or database access. The
     * controller's IO refresh releases playback; a prepared start rechecks this flag first. */
    fun foregroundHint(value: Boolean) { FocusEnvironmentBinding.foreground = value }
    fun foregroundChanged(value: Boolean) {
        FocusEnvironmentBinding.foreground = value;music.foregroundChanged(value)
    }
    companion object {
        /** Works during database recovery too. Does not open Room, start playback or grant access. */
        fun releaseOwned(context: Context,id: String? = null,expiryOnly: Boolean = false): FocusEnvironmentCleanup? {
            val store = AndroidFocusEnvironmentStore(context)
            val platform = AndroidFocusDistractions(context)
            return store.locked {
                val control = store.read();val request = control.request ?: return@locked null
                if(id != null && request.id != id) return@locked null
                if(expiryOnly) {
                    val now = runCatching { AndroidClock(context).now() }.getOrNull()
                    if(now != null && now.bootId == request.bootId && now.elapsed < request.expiresElapsed) {
                        platform.scheduleRelease(request);return@locked null
                    }
                }
                releaseFocusEnvironment(store,platform,focusMusic(context),request.id)
            }
        }
    }
}
class FocusEnvironmentExpiryReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action != AndroidFocusDistractions.ACTION) return
        val id = intent.getStringExtra(AndroidFocusDistractions.REQUEST)?.takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                AndroidFocusEnvironment.releaseOwned(context,id,expiryOnly = true)
            } catch(e: Exception) { if(e !is CancellationException) android.util.Log.w("FocusEnvironment","Own-rule cleanup requires foreground review") }
            finally { pending.finish() }
        }
    }
}
/** Only revokes an earlier contribution at boot/update. It never initializes history. */
class FocusEnvironmentRecoveryReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { AndroidFocusEnvironment.releaseOwned(context) }
            catch(e: Exception) { if(e !is CancellationException) android.util.Log.w("FocusEnvironment","Owned-rule recovery remains pending") }
            finally { pending.finish() }
        }
    }
}
