package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.TreeLimitException
import app.familygem.geni.api.GeniAuthRequiredException
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.geniId
import app.familygem.geni.map.GeniMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.folg.gedcom.model.Gedcom

sealed interface LoadResult {
    /** @param profileId The profile whose family was loaded: the survivor when the requested one was merged */
    data class Loaded(val newPeople: Int, val profileId: String? = null) : LoadResult
    data object NotAGeniTree : LoadResult
    data object NeedsLogin : LoadResult
    data class Failed(val message: String) : LoadResult {
        /** The error behind the message, to tell a missing network from the rest; not part of equality. */
        var cause: Exception? = null
            internal set
    }
    /** The profile was deleted on Geni (or merged into one already cached); [removed] if the person left the tree. */
    data class Gone(val removed: Boolean) : LoadResult
}

/** Loads the immediate family of Geni profiles into FamilyGem trees. */
class GeniLoader(
    private val client: GeniClient,
    private val store: SyncStore,
    private val host: TreeHost,
    private val now: () -> Long = System::currentTimeMillis,
    private val photos: PhotoSync? = null,
    private val onPhotoError: (String, Exception) -> Unit = { _, _ -> }
) {

    /**
     * Fetches the immediate family of a profile (3 requests) and merges it into the tree.
     * Nothing changes if any request fails.
     */
    suspend fun load(treeId: Int, geniProfileId: String): LoadResult {
        if (!host.treeExists(treeId)) return LoadResult.NotAGeniTree
        return try {
            store.tree(treeId) ?: return LoadResult.NotAGeniTree
            val family = client.immediateFamily(geniProfileId)
            val profiles = client.profiles(family.profileIds)
            profiles[geniProfileId]?.first?.takeIf { it.deleted == true }?.let { return gone(treeId, it) }
            val unions = client.unions(family.unionIds)
            val existing = store.links(treeId).associateBy { it.geniId }
            val links = host.edit(treeId) { gedcom ->
                FamilyMerger.merge(
                    gedcom, treeId, family,
                    profiles.values.map { it.first }, unions.values.map { it.first },
                    profiles.mapValues { it.value.second }, unions.mapValues { it.value.second },
                    existing, now()
                )
            }
            store.saveLinks(links)
            host.refreshScreens() // Before the photos: a download must not delay the card
            if (syncPhotos(treeId, profiles.values.map { it.first }, links)) host.refreshScreens()
            LoadResult.Loaded(newPeople = links.count { it.geniId.startsWith("profile-") && it.geniId !in existing }, profileId = geniProfileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeniAuthRequiredException) {
            LoadResult.NeedsLogin
        } catch (e: Exception) {
            LoadResult.Failed(e.message ?: e.javaClass.simpleName).also { it.cause = e }
        }
    }

    /** Main photos of the loaded profiles; a failure is reported and never fails the load. */
    private suspend fun syncPhotos(treeId: Int, profiles: List<GeniProfile>, links: List<GeniLink>): Boolean {
        val sync = photos ?: return false
        var changed = false
        val gedcomIds = links.associate { it.geniId to it.gedcomId }
        for (profile in profiles) {
            if (profile.deleted == true) continue
            val gedcomId = gedcomIds[profile.id] ?: continue
            try {
                if (sync.sync(treeId, profile, gedcomId)) changed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onPhotoError(profile.id, e)
            }
        }
        return changed
    }

    private enum class GoneAction { MISSING, KEPT, MOVED, REMOVED }

    /**
     * A profile deleted on Geni: its person leaves the tree, unless it holds local work (the user decides through the
     * conflict of its upload). A profile merged into one not cached yet moves to it: tag and link, then a load.
     */
    private suspend fun gone(treeId: Int, profile: GeniProfile): LoadResult {
        val link = store.link(treeId, profile.id) ?: return LoadResult.Gone(removed = false)
        val survivor = profile.mergedInto?.let { geniId(it) }?.takeIf { store.link(treeId, it) == null }
        var emptied = emptyList<String>()
        val action = host.edit(treeId) { gedcom ->
            val person = gedcom.people.orEmpty().firstOrNull { it.id == link.gedcomId && GeniMapper.getGeniId(it) == profile.id }
            when {
                person == null -> GoneAction.MISSING
                GoneProfiles.hasLocalWork(person, link) -> GoneAction.KEPT
                survivor != null -> GoneAction.MOVED.also { GeniMapper.setGeniId(person, survivor) }
                else -> GoneAction.REMOVED.also { emptied = GoneProfiles.remove(gedcom, person.id) }
            }
        }
        if (action == GoneAction.KEPT) return LoadResult.Gone(removed = false)
        store.deleteLink(treeId, profile.id)
        if (action == GoneAction.MOVED) {
            store.saveLinks(listOf(GeniLink(treeId, survivor!!, link.gedcomId, false, null, null, 0)))
            store.updateRoot(treeId, profile.id, survivor)
            return load(treeId, survivor)
        }
        emptied.forEach { family -> store.linkByGedcomId(treeId, family)?.let { store.deleteLink(treeId, it.geniId) } }
        host.refreshScreens()
        return LoadResult.Gone(removed = true)
    }

    /**
     * Creates the tree of the signed-in account, rooted at the profile of the account, or finishes the setup of an interrupted one.
     * Never makes a second tree. The calls are serialized, so a retry cannot overlap the attempt that failed.
     * @return ID of the tree
     * @throws AccountMismatchException if the tree on this device belongs to another account
     */
    suspend fun ensureTree(): Int = setupLock.withLock {
        val me = client.currentProfile()
        when (val own = ownership(me.id)) {
            is TreeOwnership.Own -> own.treeId.also { if (!own.complete) finishSetup(it, store.tree(it)?.rootGeniId ?: me.id) }
            TreeOwnership.Other -> throw AccountMismatchException()
            TreeOwnership.None -> create(me, me, null)
        }
    }

    /**
     * Creates a Geni tree rooted at the logged-in user's profile and loads the root's immediate family.
     * @param title Tree title, by default the name of the root profile
     * @param rootGeniId Another root than the user's own profile (developer tools; sandbox checks)
     * @return ID of the new tree
     * @throws TreeLimitException if a tree exists already
     */
    suspend fun createTree(title: String? = null, rootGeniId: String? = null): Int = setupLock.withLock {
        val me = client.currentProfile()
        val root = if (rootGeniId == null) me
        else client.profiles(listOf(rootGeniId))[rootGeniId]?.first ?: error("$rootGeniId not found")
        create(me, root, title)
    }

    private suspend fun create(owner: GeniProfile, root: GeniProfile, title: String?): Int {
        val treeId = host.createTree(title ?: root.name ?: root.id, Gedcom()) { id ->
            // Registered before the content is written, so a tree is never there without its owner
            store.deleteTree(id) // Rows left by a deleted tree that had the same ID
            store.saveTree(GeniTreeInfo(id, root.id, ownerGeniId = owner.id))
            app.familygem.geni.GeniTrees.add(id)
        }
        finishSetup(treeId, root.id)
        return treeId
    }

    /** Loads the root's family, makes the root person the diagram's start and plans the prefetch. A failed load is thrown, so a retry runs this again. */
    private suspend fun finishSetup(treeId: Int, rootGeniId: String) {
        when (val result = load(treeId, rootGeniId)) {
            is LoadResult.Loaded -> Unit
            LoadResult.NeedsLogin -> throw GeniAuthRequiredException()
            is LoadResult.Failed -> throw result.cause ?: IllegalStateException(result.message)
            else -> throw IllegalStateException("The tree of $rootGeniId could not be loaded: $result")
        }
        // The root may have moved while loading (merged profile)
        val info = store.tree(treeId) ?: return
        store.link(treeId, info.rootGeniId)?.let { host.setRoot(treeId, it.gedcomId) }
        host.schedulePrefetch(treeId, info.rootGeniId, info.prefetchDepth)
    }

    /** Who the tree on this device belongs to, seen from the signed-in account. */
    suspend fun ownership(accountId: String): TreeOwnership {
        val info = store.trees().firstOrNull { host.treeExists(it.treeId) } ?: return TreeOwnership.None
        if (info.ownerGeniId != accountId) return TreeOwnership.Other
        return TreeOwnership.Own(info.treeId, complete = store.link(info.treeId, info.rootGeniId) != null)
    }

    private companion object {
        /** One setup at a time for the whole app: every screen builds its own loader. */
        val setupLock = Mutex()
    }
}

/** The tree on this device compared with the account that signs in. */
sealed interface TreeOwnership {
    /** No tree yet. */
    data object None : TreeOwnership

    /** The tree of this account; [complete] once its root person is loaded (else the setup was interrupted and a retry finishes it). */
    data class Own(val treeId: Int, val complete: Boolean) : TreeOwnership

    /** A tree of another account: this one cannot sign in until the tree is deleted. */
    data object Other : TreeOwnership
}

/** The signed-in account is not the owner of the tree on this device. */
class AccountMismatchException : IllegalStateException("The tree belongs to another Geni account")
