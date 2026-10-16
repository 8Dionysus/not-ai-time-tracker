package dev.notai.tracker.android

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import android.service.notification.Condition
import android.system.Os
import android.system.OsConstants
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
    /** Pure cached lookup for an explicitly selected LOCAL file. No Tracker, maintenance locks,
     * URI/network lookup, copying or permission prompts in this callback. Preparation opens once. */
    @Volatile var localFile: ((String)->File?)? = null
    /** Set from the host lifecycle. False is the conservative default; no foreground service. */
    @Volatile var foreground: Boolean = false
}
private val focusEnvironmentGates = mutableMapOf<String,Any>()
/** Caller owns this stream until release. Open without waiting on a substituted FIFO, then
 * validate the opened inode rather than an earlier pathname observation. */
internal fun openFocusAudioFile(file: File): FileInputStream {
    val closeOnExec = if(Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else DescriptorOpenFlags.closeOnExec()
    val input = FileInputStream(Os.open(file.absolutePath,
        OsConstants.O_RDONLY or closeOnExec or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0))
    return try {
        val stat = Os.fstat(input.fd)
        check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size > 0) { "Local audio must be a nonempty regular file" }
        input
    } catch(e: Exception) { runCatching { input.close() };throw e }
}
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
    private fun stampPrefix(): String = "parcel-v1:${Build.VERSION.SDK_INT}:"+MessageDigest.getInstance("SHA-256")
        .digest(Build.FINGERPRINT.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }+":"
    private fun fingerprint(rule: AutomaticZenRule): String {
        val parcel = Parcel.obtain()
        return try {
            rule.writeToParcel(parcel,0)
            stampPrefix()+MessageDigest.getInstance("SHA-256").digest(parcel.marshall()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
        } finally { parcel.recycle() }
    }
    override fun prepare(request: FocusEnvironmentRequest,previous: FocusDndLease?): FocusDndPreparation {
        val available = availability()
        if(available != FocusEffectStatus.READY || Build.VERSION.SDK_INT < 29) return FocusDndPreparation(previous,available)
        if(previous != null) {
            // Parcel wire representation is not a persistent API across OS builds. Unknown
            // provenance is retained for review, never mislabeled as a user edit or reasserted.
            if(!previous.fingerprint.startsWith(stampPrefix())) return FocusDndPreparation(previous,FocusEffectStatus.OUTCOME_UNKNOWN)
            val rule = manager.getAutomaticZenRule(previous.ruleId)
            if(rule != null) {
                if(!owned(rule,previous) || fingerprint(rule) != previous.fingerprint)
                    return FocusDndPreparation(null,FocusEffectStatus.USER_CHANGED_RULE)
                return FocusDndPreparation(previous.copy(requestId = request.id),FocusEffectStatus.READY)
            }
        }
        val config = FocusEnvironmentBinding.rule ?: return FocusDndPreparation(previous,FocusEffectStatus.CONFIGURATION_REQUIRED)
        val uri = condition(request.id)
        val existing = manager.automaticZenRules.filterValues { it.conditionId == uri && it.configurationActivity == config.activity && it.owner == null }
        // An orphan or user-adopted rule is not silently reset or reactivated.
        if(existing.isNotEmpty()) return FocusDndPreparation(null,FocusEffectStatus.USER_CHANGED_RULE)
        val id = manager.addAutomaticZenRule(AutomaticZenRule(config.name,null,config.activity,uri,null,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,true)) ?: return FocusDndPreparation(null,FocusEffectStatus.FAILED)
        manager.setAutomaticZenRuleState(id,Condition(uri,config.summary,Condition.STATE_FALSE))
        val rule = manager.getAutomaticZenRule(id) ?: return FocusDndPreparation(null,FocusEffectStatus.OUTCOME_UNKNOWN)
        // Enabled is configuration; STATE_FALSE keeps the condition INACTIVE until activation.
        return FocusDndPreparation(FocusDndLease(request.id,id,uri.toString(),fingerprint(rule)),FocusEffectStatus.READY)
    }
    private fun owned(rule: AutomaticZenRule,lease: FocusDndLease): Boolean = Build.VERSION.SDK_INT >= 29 &&
        rule.conditionId.toString() == lease.conditionId && rule.conditionId == condition(lease.ruleKey) &&
        rule.owner == null && rule.configurationActivity?.packageName == context.packageName
    override fun activate(lease: FocusDndLease): FocusEffectStatus {
        if(Build.VERSION.SDK_INT < 29) return FocusEffectStatus.UNSUPPORTED
        if(!manager.isNotificationPolicyAccessGranted) return FocusEffectStatus.ACCESS_REQUIRED
        if(!lease.fingerprint.startsWith(stampPrefix())) return FocusEffectStatus.OUTCOME_UNKNOWN
        val rule = manager.getAutomaticZenRule(lease.ruleId) ?: return FocusEffectStatus.USER_CHANGED_RULE
        if(!owned(rule,lease) || fingerprint(rule) != lease.fingerprint) return FocusEffectStatus.USER_CHANGED_RULE
        val summary = FocusEnvironmentBinding.rule?.summary ?: return FocusEffectStatus.CONFIGURATION_REQUIRED
        manager.setAutomaticZenRuleState(lease.ruleId,Condition(Uri.parse(lease.conditionId),summary,Condition.STATE_TRUE))
        return FocusEffectStatus.APPLY_ACCEPTED // accepted contribution, never proof of global DND.
    }
    override fun release(lease: FocusDndLease): FocusEffectStatus {
        if(Build.VERSION.SDK_INT < 29 || !lease.fingerprint.startsWith(stampPrefix())) return FocusEffectStatus.OUTCOME_UNKNOWN
        if(!manager.isNotificationPolicyAccessGranted) return FocusEffectStatus.ACCESS_REQUIRED
        val rule = manager.getAutomaticZenRule(lease.ruleId) ?: return FocusEffectStatus.RELEASED
        if(!owned(rule,lease) || fingerprint(rule) != lease.fingerprint) return FocusEffectStatus.USER_CHANGED_RULE
        manager.setAutomaticZenRuleState(lease.ruleId,Condition(Uri.parse(lease.conditionId),FocusEnvironmentBinding.rule?.summary ?: "Focus",Condition.STATE_FALSE))
        // Android has no conditional delete. Park the inactive unchanged rule and reuse its
        // exact lease on a later explicit begin. Never delete a concurrently edited user rule.
        return FocusEffectStatus.RELEASED
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
    private var source: FileInputStream? = null
    private var focusRequest: AudioFocusRequest? = null
    private var state = FocusEffectStatus.OFF
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    @Synchronized override fun availability(localId: String?): FocusEffectStatus {
        if(Build.VERSION.SDK_INT < 27) try { DescriptorOpenFlags.closeOnExec() }
        catch(_: LinkageError) { return FocusEffectStatus.UNSUPPORTED }
        catch(_: SecurityException) { return FocusEffectStatus.UNSUPPORTED }
        if(!FocusEnvironmentBinding.foreground) return FocusEffectStatus.FOREGROUND_REQUIRED
        if(localId == null || FocusEnvironmentBinding.localFile == null) return FocusEffectStatus.SOURCE_REQUIRED
        if(manager.isMusicActive && player?.isPlaying != true) return FocusEffectStatus.OTHER_AUDIO_ACTIVE
        return FocusEffectStatus.READY
    }
    @Synchronized override fun prepare(requestId: String,localId: String): PreparedFocusMusic? {
        if(availability(localId) != FocusEffectStatus.READY) return null
        owner?.let { if(stop(it) != FocusEffectStatus.RELEASED) return null }
        val file = FocusEnvironmentBinding.localFile?.invoke(localId) ?: return null
        owner = requestId
        try {
            // Pin the exact opened inode through release. NOFOLLOW rejects a substituted final
            // symlink; fstat rejects pipes/devices regardless of an earlier path-level isFile.
            val input = openFocusAudioFile(file)
            source = input
            val p = MediaPlayer();player = p
            p.setAudioAttributes(attributes)
            p.setDataSource(input.fd)
            p.isLooping = true;p.prepare()
            state = FocusEffectStatus.READY
            p.setOnErrorListener { _,_,_ -> synchronized(this) {
                if(owner == requestId && player === p) terminate(requestId,FocusEffectStatus.FAILED)
            };true }
            return PreparedFocusMusic { startPrepared(requestId,p) }
        } catch(_: Exception) { terminate(requestId,FocusEffectStatus.FAILED);return null }
    }
    @Synchronized private fun startPrepared(requestId: String,p: MediaPlayer): FocusEffectStatus {
        if(owner != requestId || player !== p) return FocusEffectStatus.INTERRUPTED
        val ready = when {
            !FocusEnvironmentBinding.foreground -> FocusEffectStatus.FOREGROUND_REQUIRED
            manager.isMusicActive && !p.isPlaying -> FocusEffectStatus.OTHER_AUDIO_ACTIVE
            else -> FocusEffectStatus.READY
        }
        if(ready != FocusEffectStatus.READY) return terminate(requestId,ready)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if(change != AudioManager.AUDIOFOCUS_GAIN) synchronized(this) {
                    if(owner == requestId && player === p) terminate(requestId,FocusEffectStatus.INTERRUPTED)
                }
            },Handler(Looper.getMainLooper())).build()
        focusRequest = request
        return try {
            if(manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = null // A known denial never held audio focus.
                terminate(requestId,FocusEffectStatus.AUDIO_FOCUS_DENIED)
            } else { p.start();state = FocusEffectStatus.ACTIVE;state }
        } catch(_: RuntimeException) { terminate(requestId,FocusEffectStatus.OUTCOME_UNKNOWN) }
    }
    @Synchronized override fun status(requestId: String): FocusEffectStatus {
        if(owner == requestId && !FocusEnvironmentBinding.foreground) terminate(requestId,FocusEffectStatus.FOREGROUND_REQUIRED)
        return if(owner == null || owner == requestId) state else FocusEffectStatus.OFF
    }
    @Synchronized fun foregroundChanged(value: Boolean) {
        if(!value) owner?.let { terminate(it,FocusEffectStatus.FOREGROUND_REQUIRED) }
    }
    private fun terminate(requestId: String,terminal: FocusEffectStatus): FocusEffectStatus {
        val result = stop(requestId)
        state = if(result == FocusEffectStatus.RELEASED || result == FocusEffectStatus.OFF) terminal else FocusEffectStatus.OUTCOME_UNKNOWN
        return state
    }
    @Synchronized override fun stop(requestId: String): FocusEffectStatus {
        if(owner != requestId) return FocusEffectStatus.OFF
        var failed = false
        try { player?.release();player = null } catch(_: RuntimeException) { failed = true }
        // Always attempt to release audio focus, even if MediaPlayer.release failed.
        try { focusRequest?.let {
            if(manager.abandonAudioFocusRequest(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = null
            else failed = true
        } }
        catch(_: RuntimeException) { failed = true }
        if(player == null) try { source?.close();source = null } catch(_: Exception) { failed = true }
        if(!failed && player == null && source == null && focusRequest == null) owner = null
        state = if(failed) FocusEffectStatus.OUTCOME_UNKNOWN else FocusEffectStatus.RELEASED
        return state
    }
}
private val focusMusicPorts = mutableMapOf<String,AndroidFocusMusic>()
private fun focusMusic(context: Context) = synchronized(focusMusicPorts) {
    focusMusicPorts.getOrPut(context.applicationContext.noBackupFilesDir.absolutePath) { AndroidFocusMusic(context.applicationContext) }
}
class AndroidFocusEnvironment(context: Context,tracker: Tracker,active: ()->Boolean = { true }) {
    private val application = context.applicationContext
    private val music = focusMusic(application)
    val controller = FocusEnvironment(tracker,AndroidFocusEnvironmentStore(application),AndroidFocusDistractions(application),music,active)
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
