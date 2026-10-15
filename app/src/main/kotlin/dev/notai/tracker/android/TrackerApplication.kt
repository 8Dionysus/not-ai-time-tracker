package dev.notai.tracker.android

import android.app.Application

/** Process/lifetime wiring only; no UI resources, rendering, permission prompt or DB creation. */
class TrackerApplication: Application() {
    override fun onCreate() { super.onCreate();CopyLifetimes.install(this) }
    override fun onTerminate() { CopyLifetimes.get(this).shutdown();super.onTerminate() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig);TrackerRuntime.refreshExistingPlatform(this)
    }
}
