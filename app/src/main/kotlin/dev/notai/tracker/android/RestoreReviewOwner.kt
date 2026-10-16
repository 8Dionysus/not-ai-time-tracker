package dev.notai.tracker.android

/** Owns one pending restore review. Claim transfers ownership to the apply job; disposal
 * never closes a claimed resource. A late preparation is released by its exact request. */
class RestoreReviewOwner<T: AutoCloseable>(private val release: (T) -> Unit = { it.close() }): AutoCloseable {
    class Request internal constructor()
    private var closed = false
    private var request: Request? = null
    private var pending: T? = null
    @Synchronized fun begin(): Request? {
        if(closed) return null
        releasePending();return Request().also { request = it }
    }
    @Synchronized fun prepared(request: Request,value: T): T? {
        if(closed || this.request !== request) { release(value);return null }
        releasePending();pending = value;return value
    }
    @Synchronized fun claim(value: T): Boolean {
        if(closed || pending !== value) return false
        pending = null;request = null;return true
    }
    @Synchronized fun discard(value: T) {
        if(pending === value) { request = null;releasePending() }
    }
    @Synchronized fun cancel(request: Request) {
        if(this.request === request) { this.request = null;releasePending() }
    }
    @Synchronized override fun close() { closed = true;request = null;releasePending() }
    private fun releasePending() { val old = pending;pending = null;old?.let(release) }
}
