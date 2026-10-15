package dev.notai.tracker.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/** Small backend-only entry point; never launches product Activities or opens tracker.db. */
class BackendInstrumentation: Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);args = arguments ?: Bundle();start() }
    override fun onStart() {
        try {
            val report = BackendIntegrationChecks.run(targetContext,requireNotNull(args.getString("mode")),requireNotNull(args.getString("backendName")))
            finish(Activity.RESULT_OK,Bundle().apply { putString("report",report.toString()) })
        } catch(e: Throwable) {
            android.util.Log.e("BackendQA","Synthetic backend fixture failed",e)
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("error",e.stackTraceToString()) })
        }
    }
}
