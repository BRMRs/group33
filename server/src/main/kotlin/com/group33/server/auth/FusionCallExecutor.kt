package com.group33.server.auth

import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Internal trial operational limits, not the proposed D1 attempt/lease protocol. */
internal data class FusionCallPolicy(
    val connectMillis: Int = 5_000,
    val readMillis: Int = 10_000,
    val responseMillis: Long = 15_000,
) {
    init {
        require(connectMillis in 1..60_000 && readMillis in 1..60_000 && responseMillis in 1..60_000)
    }
}

internal enum class FusionStage { SDK_TOKEN, VERIFY }
internal enum class FusionOutcome { SUCCESS, DENIED, TIMEOUT, SATURATED, INTERRUPTED, FAILED }
internal data class FusionDiagnostic(val stage: FusionStage, val outcome: FusionOutcome, val elapsedMillis: Long)

/** No work queue and bounded workers: an uncooperative supplier cannot create unlimited retained calls.
 * Cancellation is best effort; a late supplier result never reaches account/ticket/session writes.
 */
internal class FusionCallExecutor(
    private val executor: ExecutorService,
    val policy: FusionCallPolicy = FusionCallPolicy(),
    private val record: (FusionDiagnostic) -> Unit = ::logDiagnostic,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(FusionCallExecutor::class.java)
        private val sequence = AtomicInteger()
        internal fun pool(workers: Int): ThreadPoolExecutor {
            require(workers in 1..16)
            return ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS, SynchronousQueue(),
                { runnable -> Thread(runnable, "group33-fusion-${sequence.incrementAndGet()}").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
        }
        private fun logDiagnostic(value: FusionDiagnostic) {
            // Only bounded enums and monotonic duration: never log an upstream exception or input.
            logger.info("phone_provider stage={} outcome={} elapsed_ms={}", value.stage, value.outcome, value.elapsedMillis)
        }
        val shared = FusionCallExecutor(pool(4))
    }

    fun <T> execute(stage: FusionStage, action: () -> T): T {
        val started = nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(policy.responseMillis)
        var outcome = FusionOutcome.FAILED
        var task: Future<T>? = null
        try {
            task = executor.submit(Callable(action))
            val remaining = budget - (nanoTime() - started)
            if (remaining <= 0) throw TimeoutException()
            val value = task.get(remaining, TimeUnit.NANOSECONDS)
            // A completed future is insufficient if the caller resumes after its deadline.
            if (nanoTime() - started >= budget) throw TimeoutException()
            outcome = if (value == null) FusionOutcome.DENIED else FusionOutcome.SUCCESS
            return value
        } catch (_: TimeoutException) {
            outcome = FusionOutcome.TIMEOUT
            throw FusionProviderException()
        } catch (_: RejectedExecutionException) {
            outcome = FusionOutcome.SATURATED
            throw FusionProviderException()
        } catch (_: InterruptedException) {
            outcome = FusionOutcome.INTERRUPTED
            Thread.currentThread().interrupt()
            throw FusionProviderException()
        } catch (_: ExecutionException) {
            throw FusionProviderException()
        } catch (_: CancellationException) {
            throw FusionProviderException()
        } finally {
            if (outcome != FusionOutcome.SUCCESS && outcome != FusionOutcome.DENIED) task?.cancel(true)
            val elapsed = (nanoTime() - started).coerceAtLeast(0) / 1_000_000
            try { record(FusionDiagnostic(stage, outcome, elapsed)) } catch (_: Exception) {
                // Observability failure must not change authentication or expose the private cause.
            }
        }
    }
}
