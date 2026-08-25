package com.jellemax.detour.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `tryClaimMigration()` backs `CredentialMigration.migrateOnce()`'s once-per-process
 * guard (issue #43 part 2). It is `expect`/`actual` because `commonMain` has no
 * synchronisation primitive of its own; this is the Android `actual`, backed by
 * `java.util.concurrent.atomic.AtomicBoolean`. Android-only for the same reason as
 * [RouteStoreLoadOrderTest]: the mechanism under test — a platform atomic — is not
 * something a shared-source test can exercise directly, but the guarantee it gives
 * (exactly one winner, no matter how many callers race in) is common to every actual.
 *
 * The guard is a single process-wide flag with no reset, so both assertions live in
 * one test method rather than two — JUnit does not guarantee method execution order,
 * and a second test method calling `tryClaimMigration()` on its own would be racing
 * this one for who claims it first instead of testing anything.
 */
class CredentialMigrationAtomicGuardTest {

    @Test
    fun exactlyOneOfManyConcurrentCallersClaimsTheMigrationAndItStaysClaimed() {
        val callers = 64
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val winners = AtomicInteger(0)

        val futures = List(callers) {
            pool.submit {
                start.await()
                if (tryClaimMigration()) winners.incrementAndGet()
            }
        }
        start.countDown()
        futures.forEach { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, winners.get())
        // A later caller, once the race is settled, still sees it as claimed rather
        // than the flag having reset.
        assertEquals(false, tryClaimMigration())
    }
}
