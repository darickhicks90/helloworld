package app.foxbox.audio

/** One process-wide spin lock: the core only locks briefly (building a drum kit). */
@PublishedApi internal object SpinLock {
    private var held = false
    fun acquire() { while (true) { if (!held) { held = true; return } } }
    fun release() { held = false }
}

actual inline fun <T> locked(lock: Any, block: () -> T): T {
    SpinLock.acquire()
    try { return block() } finally { SpinLock.release() }
}
