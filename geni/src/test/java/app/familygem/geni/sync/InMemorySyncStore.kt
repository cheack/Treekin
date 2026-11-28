package app.familygem.geni.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Sync store for tests. */
open class InMemorySyncStore : SyncStore {
    val trees = mutableMapOf<Int, GeniTreeInfo>()
    val linkMap = linkedMapOf<Pair<Int, String>, GeniLink>()
    override suspend fun tree(treeId: Int) = trees[treeId]
    override suspend fun saveTree(info: GeniTreeInfo) {
        trees[info.treeId] = info
    }
    override suspend fun updateRoot(treeId: Int, oldRoot: String, newRoot: String): Boolean {
        val tree = trees[treeId]?.takeIf { it.rootGeniId == oldRoot } ?: return false
        trees[treeId] = tree.copy(rootGeniId = newRoot)
        return true
    }
    override suspend fun trees() = trees.values.toList()
    override suspend fun link(treeId: Int, geniId: String) = linkMap[treeId to geniId]
    override suspend fun links(treeId: Int) = linkMap.values.filter { it.treeId == treeId }
    override suspend fun unionLinks(treeId: Int) = links(treeId).filter { it.geniId.startsWith("union-") }
    override suspend fun saveLinks(links: List<GeniLink>) = links.forEach { linkMap[it.treeId to it.geniId] = it }
    override suspend fun linkByGedcomId(treeId: Int, gedcomId: String) = links(treeId).firstOrNull { it.gedcomId == gedcomId }
    override suspend fun deleteLink(treeId: Int, geniId: String) {
        linkMap.remove(treeId to geniId)
    }
    override suspend fun setVerifiedHash(treeId: Int, geniId: String, gedcomId: String, snapshot: String?, hash: Int): Boolean {
        val link = linkMap[treeId to geniId]?.takeIf { it.gedcomId == gedcomId && it.snapshot == snapshot } ?: return false
        linkMap[treeId to geniId] = link.copy(verifiedHash = hash)
        return true
    }
    override suspend fun updateSnapshot(treeId: Int, geniId: String, expected: String?, snapshot: String, geniUpdatedAt: String?): Boolean {
        val link = linkMap[treeId to geniId]?.takeIf { it.snapshot == expected } ?: return false
        linkMap[treeId to geniId] = link.copy(snapshot = snapshot, geniUpdatedAt = geniUpdatedAt, verifiedHash = null)
        return true
    }
    override suspend fun setOrphaned(treeId: Int, geniId: String, orphaned: Boolean) {
        linkMap[treeId to geniId]?.let { linkMap[treeId to geniId] = it.copy(orphaned = orphaned) }
    }
    val photoLinkMap = linkedMapOf<Triple<Int, String, String>, PhotoLink>()
    override suspend fun photoLinks(treeId: Int) = photoLinkMap.values.filter { it.treeId == treeId }
    override suspend fun photoLinks(treeId: Int, geniProfileId: String) = photoLinks(treeId).filter { it.geniProfileId == geniProfileId }
    override suspend fun savePhotoLink(link: PhotoLink) {
        photoLinkMap[Triple(link.treeId, link.geniProfileId, link.geniPhotoId)] = link
    }
    override suspend fun deletePhotoLink(treeId: Int, geniProfileId: String, geniPhotoId: String) {
        photoLinkMap.remove(Triple(treeId, geniProfileId, geniPhotoId))
    }
    val feedMap = linkedMapOf<Triple<Int, FeedRecordKind, String>, FeedRecord>()
    override suspend fun saveFeedRecords(records: List<FeedRecord>, keepSinceMillis: Long) {
        records.forEach { feedMap[Triple(it.treeId, it.kind, it.geniId)] = it }
        feedMap.values.removeAll { it.atMillis < keepSinceMillis }
    }
    override suspend fun feedRecords(treeId: Int, sinceMillis: Long) = feedMap.values.filter { it.treeId == treeId && it.atMillis >= sinceMillis }
    val emailMap = linkedMapOf<Pair<Int, String>, String>()
    override suspend fun newEmails(treeId: Int) = emailMap.filterKeys { it.first == treeId }.mapKeys { it.key.second }
    override suspend fun saveNewEmail(treeId: Int, gedcomId: String, email: String?) {
        if (email.isNullOrBlank()) emailMap.remove(treeId to gedcomId) else emailMap[treeId to gedcomId] = email
    }
    override suspend fun deleteTree(treeId: Int) {
        emailMap.keys.removeAll { it.first == treeId }
        feedMap.keys.removeAll { it.first == treeId }
        photoLinkMap.keys.removeAll { it.first == treeId }
        trees.remove(treeId)
        linkMap.keys.removeAll { it.first == treeId }
        jobMap.values.removeAll { it.treeId == treeId }
        publishJobs()
    }

    val jobMap = sortedMapOf<Long, UploadJob>()
    private var lastJobId = 0L
    override suspend fun jobs(treeId: Int) = jobMap.values.filter { it.treeId == treeId }
    override suspend fun allJobs() = jobMap.values.toList()
    private val jobFlow = MutableStateFlow<List<UploadJob>>(emptyList())
    /** Emits after the store's own job writes; tests that change [jobMap] directly call [publishJobs]. */
    override fun observeJobs(): StateFlow<List<UploadJob>> = jobFlow
    fun publishJobs() {
        jobFlow.value = jobMap.values.toList()
    }
    override suspend fun saveJob(job: UploadJob): Long {
        check(job.id != 0L || jobMap.values.none { it.treeId == job.treeId && it.key == job.key }) { "Job ${job.key} already exists" }
        val id = if (job.id == 0L) ++lastJobId else job.id
        jobMap[id] = job.copy(id = id)
        publishJobs()
        return id
    }
    override suspend fun deleteJob(id: Long) {
        jobMap.remove(id)
        publishJobs()
    }
    override suspend fun deleteJobIfVersion(id: Long, version: Int): Boolean =
        jobMap[id]?.takeIf { it.version == version }?.let { jobMap.remove(id); publishJobs(); true } ?: false
}
