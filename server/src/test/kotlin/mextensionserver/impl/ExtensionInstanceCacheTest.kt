package mextensionserver.impl

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExtensionInstanceCacheTest {
    @Test
    fun `reuses mutable extension state for identical data`() {
        val loads = AtomicInteger()
        val cache =
            ExtensionInstanceCache(
                keyOf = ByteArray::contentToString,
                load = {
                    loads.incrementAndGet()
                    MutableExtension()
                },
                dispose = {},
            )

        val first = cache.use(byteArrayOf(1)) { extension -> ++extension.page }
        val second = cache.use(byteArrayOf(1)) { extension -> ++extension.page }
        cache.use(byteArrayOf(2)) { extension -> ++extension.page }

        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(2, loads.get())
        cache.close()
    }

    @Test
    fun `reuses an extension by its issued key`() {
        val cache =
            ExtensionInstanceCache(
                keyOf = ByteArray::contentToString,
                load = { MutableExtension() },
                dispose = {},
            )

        val first = cache.useAndGetKey(byteArrayOf(1)) { extension -> ++extension.page }
        val second = cache.useByKey(first.key) { extension -> ++extension.page }

        assertEquals(1, first.value)
        assertEquals(2, second?.value)
        assertEquals(null, cache.useByKey("missing") { extension -> ++extension.page })
        cache.close()
    }

    @Test
    fun `serializes calls to one extension instance`() {
        val activeCalls = AtomicInteger()
        val maxActiveCalls = AtomicInteger()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        val cache =
            ExtensionInstanceCache(
                keyOf = ByteArray::contentToString,
                load = { MutableExtension() },
                dispose = {},
            )

        try {
            val futures =
                List(4) {
                    executor.submit {
                        start.await()
                        cache.use(byteArrayOf(1)) {
                            val active = activeCalls.incrementAndGet()
                            maxActiveCalls.updateAndGet { current -> maxOf(current, active) }
                            Thread.sleep(25)
                            activeCalls.decrementAndGet()
                        }
                    }
                }
            start.countDown()
            futures.forEach { future -> future.get(5, TimeUnit.SECONDS) }

            assertEquals(1, maxActiveCalls.get())
        } finally {
            cache.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `loads different extensions at the same time`() {
        // Loading converts the whole APK. One global lock around it made every
        // other request, even for extensions already loaded, wait for it, so a
        // launch converted 59 APKs one after another.
        val secondStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val cache =
            ExtensionInstanceCache(
                keyOf = ByteArray::contentToString,
                load = { data ->
                    if (data[0] == 1.toByte()) {
                        check(secondStarted.await(5, TimeUnit.SECONDS)) { "loads ran one at a time" }
                    } else {
                        secondStarted.countDown()
                    }
                    MutableExtension()
                },
                dispose = {},
            )

        try {
            val first = executor.submit { cache.use(byteArrayOf(1)) {} }
            Thread.sleep(50)
            val second = executor.submit { cache.use(byteArrayOf(2)) {} }
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)
        } finally {
            cache.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `loads one extension once when it is asked for twice at the same time`() {
        val loads = AtomicInteger()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        val cache =
            ExtensionInstanceCache(
                keyOf = ByteArray::contentToString,
                load = {
                    loads.incrementAndGet()
                    Thread.sleep(50)
                    MutableExtension()
                },
                dispose = {},
            )

        try {
            List(4) {
                executor.submit {
                    start.await()
                    cache.use(byteArrayOf(1)) {}
                }
            }.also { start.countDown() }
                .forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, loads.get())
        } finally {
            cache.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `close disposes cached instances and allows a clean reload`() {
        val loads = AtomicInteger()
        val disposals = AtomicInteger()
        val cache =
            ExtensionInstanceCache(
                keyOf = ByteArray::contentToString,
                load = {
                    loads.incrementAndGet()
                    MutableExtension()
                },
                dispose = { disposals.incrementAndGet() },
            )

        cache.use(byteArrayOf(1)) {}
        cache.close()
        cache.use(byteArrayOf(1)) {}
        cache.close()

        assertEquals(2, loads.get())
        assertEquals(2, disposals.get())
    }

    @Test
    fun `does not cache a failed load`() {
        var attempts = 0
        val cache =
            ExtensionInstanceCache(
                keyOf = ByteArray::contentToString,
                load = {
                    attempts++
                    if (attempts == 1) error("load failed")
                    MutableExtension()
                },
                dispose = {},
            )

        assertFailsWith<IllegalStateException> {
            cache.use(byteArrayOf(1)) {}
        }
        cache.use(byteArrayOf(1)) {}

        assertEquals(2, attempts)
        cache.close()
    }

    private class MutableExtension(
        var page: Int = 0,
    )
}
