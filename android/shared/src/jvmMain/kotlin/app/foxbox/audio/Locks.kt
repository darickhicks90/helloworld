package app.foxbox.audio

actual inline fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)
