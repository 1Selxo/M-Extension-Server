package mextensionserver.impl

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import mextensionserver.model.JFilterList
import mextensionserver.model.JGroupFilter
import kotlin.test.Test
import kotlin.test.assertTrue

class MihonInvokerTest {
    @Test
    fun `converts children of grouped anime filters`() {
        val child = TestCheckBox()
        val originalFilters =
            AnimeFilterList(
                TestGroup(listOf(child)),
            )
        val requestedFilters =
            listOf(
                JFilterList(
                    name = "Group",
                    type = null,
                    stateString = null,
                    stateInt = null,
                    stateList =
                        listOf(
                            JGroupFilter(
                                name = "Child",
                                type = null,
                                stateBoolean = true,
                                stateInt = null,
                            ),
                        ),
                    stateSort = null,
                ),
            )

        convertAnimeFilterList(originalFilters, requestedFilters)

        assertTrue(child.state)
    }

    private fun convertAnimeFilterList(
        originalFilters: AnimeFilterList,
        requestedFilters: List<JFilterList>,
    ) {
        val method =
            MihonInvoker::class.java.getDeclaredMethod(
                "convertAnimeFilterList",
                AnimeFilterList::class.java,
                List::class.java,
            )
        method.isAccessible = true
        method.invoke(MihonInvoker, originalFilters, requestedFilters)
    }

    private class TestCheckBox : AnimeFilter.CheckBox("Child")

    private class TestGroup(
        children: List<TestCheckBox>,
    ) : AnimeFilter.Group<TestCheckBox>("Group", children)
}
