package app.familygem.geni.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Tells open screens that the main photo of a person changed in the tree (a delete Geni confirmed, or its replacement). */
object PersonPhotoChanges {
    private val changes = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun notify(treeId: Int, personGedcomId: String) {
        changes.tryEmit(treeId to personGedcomId)
    }

    /** One item per change of this person of this tree. */
    fun of(treeId: Int, personGedcomId: String): Flow<Unit> =
        changes.filter { it.first == treeId && it.second == personGedcomId }.map { }
}
