package com.group33.server.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FusionCallExecutorTest {
    private fun awaitIgnoringInterrupt(release: CountDownLatch) {
        while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
    }

    @Test fun `late supplier success cannot escape the bounded caller`() {
        val pool = FusionCallExecutor.pool(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val records = mutableListOf<FusionDiagnostic>()
        val calls = FusionCallExecutor(pool, FusionCallPolicy(responseMillis = 60), records::add)
        try {
            val failure = assertThrows(FusionProviderException::class.java) {
                calls.execute(FusionStage.VERIFY) {
                    try { awaitIgnoringInterrupt(release); "PRIVATE_LATE_PHONE" } finally { finished.countDown() }
                }
            }
            assertEquals("Fusion authentication service unavailable", failure.message)
            assertNull(failure.cause)
            assertEquals(FusionOutcome.TIMEOUT, records.single().outcome)
            assertTrue(records.single().elapsedMillis >= 40)
            assertEquals(1, finished.count)
            release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, records.size)
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun `saturated pool rejects without queueing or executing another proof`() {
        val pool = FusionCallExecutor.pool(2)
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val outsiders = Executors.newFixedThreadPool(2)
        val count = AtomicInteger()
        val calls = FusionCallExecutor(pool, FusionCallPolicy(responseMillis = 2_000))
        try {
            val work = (1..2).map { outsiders.submit<String> { calls.execute(FusionStage.VERIFY) {
                count.incrementAndGet(); entered.countDown(); release.await(); "fictional"
            } } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val diagnostics = mutableListOf<FusionDiagnostic>()
            val busy = FusionCallExecutor(pool, record = diagnostics::add)
            assertThrows(FusionProviderException::class.java) { busy.execute(FusionStage.VERIFY) { count.incrementAndGet() } }
            assertEquals(FusionOutcome.SATURATED, diagnostics.single().outcome)
            assertEquals(2, count.get()); assertEquals(0, pool.queue.size)
            release.countDown(); work.forEach { assertEquals("fictional", it.get(2, TimeUnit.SECONDS)) }
        } finally { release.countDown(); outsiders.shutdownNow(); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun `caller interruption retains interrupt and discards future result`() {
        val pool = FusionCallExecutor.pool(1)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(); val done = CountDownLatch(1)
        val outcomes = mutableListOf<FusionDiagnostic>()
        val calls = FusionCallExecutor(pool, record = outcomes::add)
        val caller = Thread {
            try {
                assertThrows(FusionProviderException::class.java) { calls.execute(FusionStage.VERIFY) {
                    entered.countDown(); awaitIgnoringInterrupt(release); "PRIVATE_LATE_PHONE"
                } }
                interrupted.set(Thread.currentThread().isInterrupted)
            } finally { done.countDown() }
        }
        try {
            caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); caller.interrupt()
            assertTrue(done.await(2, TimeUnit.SECONDS)); assertTrue(interrupted.get())
            assertEquals(FusionOutcome.INTERRUPTED, outcomes.single().outcome)
        } finally { release.countDown(); caller.join(2_000); pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun `upstream private message is absent from exception and diagnostics`() {
        val pool = FusionCallExecutor.pool(1); val records = mutableListOf<FusionDiagnostic>()
        try {
            val calls = FusionCallExecutor(pool, record = records::add)
            val failure = assertThrows(FusionProviderException::class.java) {
                calls.execute(FusionStage.SDK_TOKEN) { error("PRIVATE_PROOF_PHONE_KEY") }
            }
            assertNull(failure.cause); assertFalse(failure.toString().contains("PRIVATE_"))
            assertEquals(FusionOutcome.FAILED, records.single().outcome)
            assertFalse(records.toString().contains("PRIVATE_"))
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun `completion observed after monotonic deadline cannot publish success`() {
        val pool = FusionCallExecutor.pool(1); val ticks = AtomicInteger(); val records = mutableListOf<FusionDiagnostic>()
        try {
            val calls = FusionCallExecutor(pool, FusionCallPolicy(responseMillis = 100), records::add) {
                if (ticks.getAndIncrement() < 2) 0 else TimeUnit.MILLISECONDS.toNanos(101)
            }
            assertThrows(FusionProviderException::class.java) { calls.execute(FusionStage.VERIFY) { "late" } }
            assertEquals(FusionOutcome.TIMEOUT, records.single().outcome)
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun `diagnostic failure does not change accepted supplier result`() {
        val pool = FusionCallExecutor.pool(1)
        try {
            val calls = FusionCallExecutor(pool, record = { error("unavailable sink") })
            assertEquals("fictional", calls.execute(FusionStage.SDK_TOKEN) { "fictional" })
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }
}
