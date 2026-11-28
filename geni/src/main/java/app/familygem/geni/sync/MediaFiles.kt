package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.folg.gedcom.model.Gedcom
import java.io.File

/** Files of the tree's media folder shared by photos and documents: two profiles can share one Geni file. */
internal object MediaFiles {

    /**
     * Deletes [fileName] only when no photo or document link uses it any more and no media in the tree still names it
     * (a photo kept as the user's own has no link).
     */
    suspend fun deleteIfUnused(store: SyncStore, host: TreeHost, treeId: Int, fileName: String?) {
        if (fileName == null || store.photoLinks(treeId).any { it.fileName == fileName }) return
        if (host.edit(treeId) { gedcom -> names(gedcom).any { it == fileName } }) return
        withContext(Dispatchers.IO) { File(host.mediaDir(treeId), fileName).delete() }
    }

    /** File names (without folders) of every media of people, families, their events and the shared media. */
    fun names(gedcom: Gedcom): Sequence<String> {
        val holders = gedcom.people.orEmpty().asSequence().flatMap { sequenceOf(it.media.orEmpty()) + it.eventsFacts.orEmpty().map { e -> e.media.orEmpty() } } +
                gedcom.families.orEmpty().asSequence().flatMap { sequenceOf(it.media.orEmpty()) + it.eventsFacts.orEmpty().map { e -> e.media.orEmpty() } } +
                sequenceOf(gedcom.media.orEmpty())
        return holders.flatMap { it.asSequence() }.mapNotNull { it.file?.substringAfterLast('/')?.substringAfterLast('\\') }
    }
}
