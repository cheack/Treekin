package app.familygem.geni.home

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupTreeTest {

    @Test
    fun lastOpenedTreeWins() = assertEquals(2, StartupTree.pick(2, listOf(1, 2, 3)))

    @Test
    fun soleTreeWithoutValidLastOpened() {
        assertEquals(5, StartupTree.pick(0, listOf(5)))
        assertEquals(5, StartupTree.pick(9, listOf(5)))
    }

    @Test
    fun noChoiceAmongSeveralTrees() {
        assertEquals(0, StartupTree.pick(9, listOf(1, 2)))
        assertEquals(0, StartupTree.pick(-1, listOf(1, 2)))
    }

    @Test
    fun noTrees() = assertEquals(0, StartupTree.pick(3, emptyList()))
}
