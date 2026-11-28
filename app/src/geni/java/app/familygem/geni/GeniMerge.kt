package app.familygem.geni

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.familygem.Global
import app.familygem.U
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.sync.MergeResult
import app.familygem.geni.sync.ProfileMerger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.folg.gedcom.model.Person
import java.util.concurrent.ConcurrentHashMap

/** "Merge on Geni" of the profile menu: the open profile survives, the chosen duplicate is merged into it by Geni. */
object GeniMerge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Pairs being merged now: a second tap must not send a second merge. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** A person of the tree with the label the dialogs show. */
    private class Candidate(val geniId: String, val label: String)

    private fun label(person: Person): String {
        val name = U.properName(person).ifBlank { person.id }
        val dates = U.twoDates(person, false)
        return if (dates.isBlank()) name else "$name\n$dates"
    }

    private fun candidates(survivorId: String): List<Candidate> {
        val seen = mutableSetOf(survivorId)
        return Global.gc?.people.orEmpty().mapNotNull { person ->
            val id = GeniMapper.getGeniId(person) ?: return@mapNotNull null
            if (!id.startsWith("profile-") || !seen.add(id)) null else Candidate(id, label(person))
        }.sortedBy { it.label.lowercase() }
    }

    fun show(activity: Activity, treeId: Int, survivorId: String, survivor: Person, onDone: () -> Unit) {
        val others = candidates(survivorId)
        if (others.isEmpty()) {
            Toast.makeText(activity, R.string.geni_merge_none, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity).setTitle(R.string.geni_merge_pick)
            .setItems(others.map { "${it.label.replace('\n', ' ')} · ${it.geniId}" }.toTypedArray()) { _, which -> confirm(activity, treeId, survivorId, label(survivor), others[which], onDone) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirm(activity: Activity, treeId: Int, survivorId: String, survivorLabel: String, duplicate: Candidate, onDone: () -> Unit) {
        AlertDialog.Builder(activity).setTitle(R.string.geni_merge)
            .setMessage(activity.getString(R.string.geni_merge_confirm, duplicate.label, survivorLabel))
            .setPositiveButton(R.string.geni_merge_do) { _, _ -> run(activity, treeId, survivorId, duplicate.geniId, onDone) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun run(activity: Activity, treeId: Int, survivorId: String, absorbedId: String, onDone: () -> Unit) {
        val key = "$treeId:$survivorId:$absorbedId"
        if (!inFlight.add(key)) {
            Toast.makeText(activity, R.string.geni_merge_busy, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, R.string.geni_merge_started, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = try {
                val store = Geni.store(activity)
                // The account is the one this tree belongs to: another login sends nothing
                merger(activity).merge(treeId, survivorId, absorbedId, store.tree(treeId)?.ownerGeniId?.ifEmpty { null })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GeniSync", "Merge of $absorbedId into $survivorId failed", e)
                MergeResult.Unknown
            } finally {
                inFlight.remove(key)
            }
            withContext(Dispatchers.Main) { report(activity, treeId, survivorId, absorbedId, result, onDone) }
        }
    }

    private fun merger(activity: Activity) = ProfileMerger(Geni.client(activity), Geni.store(activity), Geni.loader(activity))

    private fun report(activity: Activity, treeId: Int, survivorId: String, absorbedId: String, result: MergeResult, onDone: () -> Unit) {
        val message = when (result) {
            MergeResult.Merged -> R.string.geni_merge_done
            MergeResult.RefreshNeeded -> R.string.geni_merge_refresh_needed
            MergeResult.Requested -> R.string.geni_merge_requested
            MergeResult.NotCompleted -> R.string.geni_merge_not_completed
            MergeResult.Unknown -> R.string.geni_merge_unknown
            is MergeResult.Rejected -> R.string.geni_merge_rejected
            MergeResult.NeedsLogin -> R.string.geni_login_again
            MergeResult.AccountChanged -> R.string.geni_merge_login_changed
            MergeResult.NotLinked -> R.string.geni_merge_not_linked
        }
        if (result == MergeResult.Merged || result == MergeResult.RefreshNeeded) onDone()
        if (result == MergeResult.RefreshNeeded || result == MergeResult.Unknown) {
            // Reads only: safe to repeat, never sends the merge again
            AlertDialog.Builder(activity).setMessage(message)
                .setPositiveButton(R.string.geni_merge_retry) { _, _ -> retry(activity, treeId, survivorId, absorbedId, onDone) }
                .setNegativeButton(android.R.string.cancel, null).show()
        } else Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun retry(activity: Activity, treeId: Int, survivorId: String, absorbedId: String, onDone: () -> Unit) {
        val key = "$treeId:$survivorId:$absorbedId"
        if (!inFlight.add(key)) return
        scope.launch {
            val result = try {
                merger(activity).reconcile(treeId, survivorId, absorbedId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GeniSync", "Refresh after the merge of $absorbedId failed", e)
                MergeResult.Unknown
            } finally {
                inFlight.remove(key)
            }
            withContext(Dispatchers.Main) { report(activity, treeId, survivorId, absorbedId, result, onDone) }
        }
    }
}
