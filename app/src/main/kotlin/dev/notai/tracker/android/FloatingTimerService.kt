package dev.notai.tracker.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import dev.notai.tracker.*
import kotlinx.coroutines.*

/** This service exists solely while the user-enabled floating controls are visible.
 * It does not keep time. No boot receiver, sticky restart, alarm, wake lock or periodic DB write. */
class FloatingTimerService: Service() {
    private val scope = MainScope()
    private var panel: LinearLayout? = null
    private var observation: Job? = null
    private val windows get() = getSystemService(WindowManager::class.java)
    private val permissionWatcher = AppOpsManager.OnOpChangedListener { _,_ ->
        if(!TimerSurfaces.overlayAllowed(this)) stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,packageName,permissionWatcher)
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(!TimerSurfaces.overlayAllowed(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val notification = TimerSurfaces.notification(this,null,overlay = true)
            if(Build.VERSION.SDK_INT >= 34) startForeground(TimerSurfaces.OVERLAY_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(TimerSurfaces.OVERLAY_ID,notification)
        } catch(_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        if(observation == null) observation = scope.launch {
            try {
                val tracker = withContext(Dispatchers.IO) { TrackerRuntime.get(this@FloatingTimerService).tracker }
                tracker.views.collect { view ->
                    if(!view.data.settings.surfaces.overlay || !TimerSurfaces.overlayAllowed(this@FloatingTimerService)) { stopSelf(); return@collect }
                    render(tracker,view.snapshot())
                }
            } catch(e: Exception) { if(e is CancellationException) throw e; stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun render(tracker: Tracker,s: Snapshot) {
        val box = panel ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            windows.addView(this,WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; y = 160 })
            panel = this
        }
        box.removeAllViews()
        fun control(label: String,cmd: TimerCommand?) {
            box.addView(Button(this).apply {
                text = label; contentDescription = label
                setOnClickListener {
                    if(cmd == null) {
                        try { TimerSurfaces.open(this@FloatingTimerService).send() } catch(_: PendingIntent.CanceledException) { }
                    } else scope.launch {
                        try { withContext(Dispatchers.IO) { TimerSurfaces.execute(tracker,SurfaceAction(Surface.OVERLAY,cmd,s.token),TimerSurfaces.overlayAllowed(this@FloatingTimerService)) } }
                        catch(e: Exception) { if(e is CancellationException) throw e; Toast.makeText(this@FloatingTimerService,"Обновите состояние в приложении",Toast.LENGTH_SHORT).show() }
                    }
                }
            })
        }
        val command = TimerSurfaces.primary(s.mode)
        control(command?.name ?: "Уточнить время",command)
        if(s.mode in setOf(Mode.RUNNING,Mode.PAUSED)) control("STOP",TimerCommand.STOP)
        control("Открыть настройки",null)
    }
    override fun onDestroy() {
        scope.cancel()
        getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionWatcher)
        panel?.let { try { windows.removeView(it) } catch(_: IllegalArgumentException) { } }; panel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        /** Only the resumed Activity calls this; never rely on a background-start exemption. */
        fun fromVisibleActivity(activity: Activity,settings: SurfaceSettings): String {
            if(!settings.overlay) { activity.stopService(Intent(activity,FloatingTimerService::class.java)); return "Плавающая кнопка выключена" }
            if(!TimerSurfaces.overlayAllowed(activity)) return "Плавающая кнопка: разрешение ОС не предоставлено; таймер доступен в приложении"
            return try {
                activity.startForegroundService(Intent(activity,FloatingTimerService::class.java))
                "Плавающая кнопка запрошена; системный индикатор сервиса обязателен"
            } catch(_: RuntimeException) { "ОС не разрешила запуск плавающей кнопки; таймер доступен в приложении" }
        }
    }
}
