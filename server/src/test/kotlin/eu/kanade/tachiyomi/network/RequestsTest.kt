package eu.kanade.tachiyomi.network

import kotlin.test.Test
import kotlin.test.assertTrue

class RequestsTest {
    @Test
    fun `exports the suspend post ABI used by current extensions`() {
        val methods = Class.forName("eu.kanade.tachiyomi.network.RequestsKt").declaredMethods

        assertTrue(methods.any { it.name == "post" && it.parameterCount == 6 })
        assertTrue(methods.any { it.name == "post\$default" && it.parameterCount == 8 })
    }
}
