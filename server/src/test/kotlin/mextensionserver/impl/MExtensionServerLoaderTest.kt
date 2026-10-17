package mextensionserver.impl

import android.content.pm.PackageInfo
import java.net.URLClassLoader
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class MExtensionServerLoaderTest {
    @Test
    fun `factory children are recreated after preferences change`() {
        var configuredChildren = 1
        val jar = createTempFile(prefix = "mextensionserver-test-", suffix = ".jar").toFile()
        val classLoader = URLClassLoader(emptyArray())
        val extension =
            MExtensionServerLoader.LoadedExtension(
                initialSources = listOf("source-1"),
                packageInfo = PackageInfo(),
                jarFile = jar,
                classLoader = classLoader,
                sourceFactory = {
                    (1..configuredChildren).map { "source-$it" }
                },
            )

        configuredChildren = 3

        assertEquals(
            listOf("source-1", "source-2", "source-3"),
            extension.refreshFactorySources(),
        )
        assertEquals(3, extension.sources.size)
        extension.close()
    }

    @Test
    fun `unchanged factory children keep their instances`() {
        val addresses = mutableMapOf(1L to "http://one.test", 2L to "http://two.test")
        val extension =
            MExtensionServerLoader.LoadedExtension(
                initialSources = emptyList(),
                packageInfo = PackageInfo(),
                jarFile = createTempFile(prefix = "mextensionserver-test-", suffix = ".jar").toFile(),
                classLoader = URLClassLoader(emptyArray()),
                sourceFactory = {
                    addresses.map { (id, address) -> FakeAnimeSource(id, address) }
                },
            )
        val (first, second) = extension.refreshFactorySources()

        addresses[2L] = "http://moved.test"
        addresses[3L] = "http://three.test"
        val refreshed = extension.refreshFactorySources()

        // A running login in the first child must stay cancellable.
        assertSame(first, refreshed[0])
        assertNotSame(second, refreshed[1])
        assertEquals("http://moved.test", (refreshed[1] as FakeAnimeSource).baseUrl)
        assertEquals(3, refreshed.size)
        extension.close()
    }

    private class FakeAnimeSource(
        override val id: Long,
        val baseUrl: String,
    ) : eu.kanade.tachiyomi.animesource.AnimeSource {
        override val name = "Fake ($id)"
        override val lang = "all"
    }
}
