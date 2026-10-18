package com.bonsaponsi.baptv

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * Single-threaded background task queue that doesn't kill the process. An unhandled exception in a background thread
 * reaches Android's default handler and ends the process (along with recording), therefore:
 * - task exceptions are caught and logged,
 * - interruption ([shutdownNow] when the activity closes) ends the task silently,
 * - tasks submitted after [shutdownNow] are skipped instead of throwing `RejectedExecutionException`.
 */
class BackgroundWorker(private val name: String) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }

    val isShutdown get() = executor.isShutdown

    fun execute(block: () -> Unit) {
        if (executor.isShutdown) return
        try {
            executor.execute { runGuarded(name, block) }
        } catch (e: RejectedExecutionException) {
            // Queue closed in the meantime – the activity is finishing.
        }
    }

    fun shutdownNow() {
        executor.shutdownNow()
    }

    companion object {
        /** Runs [block], turning any exception into a log entry. */
        fun runGuarded(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Throwable) {
                if (Thread.currentThread().isInterrupted) return
                DebugLog.error("Worker", "error in background task $name", e)
            }
        }

        /** Background thread with the same protection as [BackgroundWorker]. */
        fun start(name: String, block: () -> Unit): Thread = thread(name = name, isDaemon = true) { runGuarded(name, block) }
    }
}
