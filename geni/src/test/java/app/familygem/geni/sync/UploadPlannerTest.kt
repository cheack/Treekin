package app.familygem.geni.sync

import app.familygem.geni.map.EventFields
import app.familygem.geni.map.ProfileFields
import app.familygem.geni.map.SnapshotOverlay
import app.familygem.geni.map.UnionFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPlannerTest {

    private val ivanJson = """{"id":"profile-1","first_name":"Ivan","last_name":"User","gender":"male","is_alive":true,
        "occupation":"Engineer","birth":{"date":{"year":1960}}}"""
    private val annaJson = """{"id":"profile-2","first_name":"Anna","last_name":"User","gender":"female","is_alive":true}"""
    private val unionJson = """{"id":"union-1","status":"spouse","partners":["profile-1","profile-2"],"children":[]}"""
    private val ivan = ProfileFields.ofSnapshot(ivanJson)!!
    private val anna = ProfileFields.ofSnapshot(annaJson)!!
    private val newFields = ProfileFields.EMPTY.copy(given = "New", surname = "User")

    private fun person(id: String, geniId: String?, fields: ProfileFields) = LocalPerson(id, geniId, listOfNotNull(fields.given, fields.surname).joinToString(" ").ifEmpty { id }, fields)
    private fun family(id: String, geniId: String?, partners: List<String>, children: List<String> = emptyList(), fields: UnionFields = UnionFields.EMPTY) =
        LocalFamily(id, geniId, partners, children, fields)
    private fun link(geniId: String, gedcomId: String, snapshot: String?, hash: Int? = null) =
        GeniLink(1, geniId, gedcomId, true, null, snapshot, 0, hash)
    private fun job(key: String) = UploadJob(id = 5, treeId = 1, key = key, kind = JobKind.UPDATE_PROFILE, params = listOf("a" to "b"), label = "x", localHash = 0)

    private val coupleLinks = listOf(link("profile-1", "I1", ivanJson), link("profile-2", "I2", annaJson), link("union-1", "F1", unionJson))
    private fun couple(vararg extraPeople: LocalPerson, ivanFields: ProfileFields = ivan, children: List<String> = emptyList(), extraFamilies: List<LocalFamily> = emptyList()) =
        LocalTree(
            listOf(person("I1", "profile-1", ivanFields), person("I2", "profile-2", anna)) + extraPeople,
            listOf(family("F1", "union-1", listOf("I1", "I2"), children)) + extraFamilies
        )

    private fun plan(local: LocalTree, links: List<GeniLink> = coupleLinks, jobs: List<UploadJob> = emptyList()) =
        UploadPlanner.plan(1, local, links, jobs)

    @Test
    fun unchangedPersonIsVerified() {
        val plan = plan(couple())
        assertEquals(emptyList<UploadJob>(), plan.upserts)
        assertTrue("profile:profile-1" in plan.deleteKeys)
        assertEquals(ivan.hashCode(), plan.verified.single { it.geniId == "profile-1" }.verifiedHash)
        assertEquals(UnionFields.EMPTY.hashCode(), plan.verified.single { it.geniId == "union-1" }.verifiedHash)
    }

    @Test
    fun changedOccupationMakesUpdate() {
        val changed = ivan.copy(occupation = "Driver")
        val job = plan(couple(ivanFields = changed)).upserts.single()
        assertEquals("profile:profile-1", job.key)
        assertEquals(JobKind.UPDATE_PROFILE, job.kind)
        assertEquals("profile-1", job.geniId)
        assertEquals(listOf("occupation" to "Driver"), job.params)
        assertEquals(changed.hashCode(), job.localHash)
        assertEquals("Ivan User", job.label)
        assertEquals(0L, job.id)
    }

    @Test
    fun changedMarriageMakesUnionUpdate() {
        val local = couple().let { it.copy(families = listOf(it.families[0].copy(fields = UnionFields(EventFields("2012", null), null)))) }
        val job = plan(local).upserts.single()
        assertEquals("union:union-1", job.key)
        assertEquals(JobKind.UPDATE_UNION, job.kind)
        assertEquals("2012", job.params.toMap()["marriage[date][year]"])
        assertEquals("Ivan User & Anna User", job.label)
    }

    @Test
    fun verifiedHashSkipsPerson() {
        val changed = ivan.copy(occupation = "Driver")
        val links = coupleLinks.map { if (it.geniId == "profile-1") it.copy(verifiedHash = changed.hashCode()) else it }
        val plan = plan(couple(ivanFields = changed), links, listOf(job("profile:profile-1")))
        assertEquals(emptyList<UploadJob>(), plan.upserts)
        assertFalse("profile:profile-1" in plan.deleteKeys)
    }

    @Test
    fun revertedEditDeletesJob() {
        assertTrue("profile:profile-1" in plan(couple(), jobs = listOf(job("profile:profile-1"))).deleteKeys)
    }

    @Test
    fun personWithLostTagIsStillLinked() {
        val local = couple().let { it.copy(people = listOf(it.people[0].copy(geniId = null, fields = ivan.copy(occupation = "Driver"))) + it.people.drop(1)) }
        val job = plan(local).upserts.single()
        assertEquals("profile:profile-1", job.key)
    }

    @Test
    fun taggedPersonWithoutLinkIsSkipped() {
        val local = couple(person("I9", "profile-9", newFields))
        assertEquals(emptyList<UploadJob>(), plan(local).upserts)
    }

    @Test
    fun newChildOfGeniUnionMakesAddChild() {
        val job = plan(couple(person("I3", null, newFields), children = listOf("I3"))).upserts.single()
        assertEquals("add:I3", job.key)
        assertEquals(JobKind.ADD_CHILD, job.kind)
        assertEquals("I3", job.personGedcomId)
        assertEquals("F1", job.familyGedcomId)
        assertEquals(listOf("partner:I1", "partner:I2"), job.anchors)
        assertEquals("true", job.params.toMap()["is_alive"])
        assertEquals("New", job.params.toMap()["first_name"])
    }

    @Test
    fun newParentOfGeniPersonMakesAddPartnerWithChildAnchor() {
        val local = couple(person("I5", null, newFields), extraFamilies = listOf(family("F2", null, listOf("I5"), listOf("I1"))))
        val job = plan(local).upserts.single()
        assertEquals(JobKind.ADD_PARTNER, job.kind)
        assertEquals("F2", job.familyGedcomId)
        assertEquals(listOf("child:I1"), job.anchors)
    }

    @Test
    fun chainOfNewPeopleIsOrdered() {
        // A new spouse P of Ivan, and their new child C, in a new family
        val local = couple(
            person("I6", null, newFields), person("I7", null, newFields.copy(given = "Kid")),
            extraFamilies = listOf(family("F3", null, listOf("I1", "I6"), listOf("I7")))
        )
        val jobs = plan(local).upserts
        assertEquals(listOf("add:I6", "add:I7"), jobs.map { it.key })
        assertEquals(listOf("child:I7", "partner:I1"), jobs[0].anchors)
        assertEquals(listOf("partner:I1", "partner:I6"), jobs[1].anchors)
    }

    @Test
    fun chainThroughLaterFamilyIsOrdered() {
        // F5 (new person B, child of new person A) comes before F4 (A, child of the Geni couple) in the file
        val local = couple(
            person("I10", null, newFields.copy(given = "A")), person("I11", null, newFields.copy(given = "B")),
            children = listOf("I10"),
            extraFamilies = listOf(family("F5", null, listOf("I10"), listOf("I11")))
        ).let { it.copy(families = it.families.reversed()) }
        assertEquals(listOf("add:I10", "add:I11"), plan(local).upserts.map { it.key })
    }

    @Test
    fun unconnectedNewPersonMakesNoJob() {
        assertEquals(emptyList<UploadJob>(), plan(couple(person("I8", null, newFields))).upserts)
    }

    @Test
    fun deletedPersonDropsItsJobs() {
        val links = coupleLinks + link("profile-9", "I9", annaJson)
        val plan = plan(couple(), links, listOf(job("profile:profile-9"), job("add:I8")))
        assertTrue("profile:profile-9" in plan.deleteKeys)
        assertTrue("add:I8" in plan.deleteKeys)
    }

    @Test
    fun linkedPersonDropsItsAddJob() {
        assertTrue("add:I1" in plan(couple(), jobs = listOf(job("add:I1"))).deleteKeys)
    }

    @Test
    fun linkBetweenExistingProfilesMakesNoJob() {
        val local = couple(extraFamilies = listOf(family("F9", null, listOf("I1", "I2"))))
        assertEquals(emptyList<UploadJob>(), plan(local).upserts)
    }

    @Test
    fun unparseableDateMakesNoJob() {
        assertEquals(emptyList<UploadJob>(), plan(couple(ivanFields = ivan.copy(birth = EventFields("FROM 1960", null)))).upserts)
    }

    @Test
    fun legacyStartOfARangeMakesNoJob() {
        val ranged = ivanJson.replace("\"birth\":{\"date\":{\"year\":1960}}", "\"birth\":{\"date\":{\"year\":1960,\"range\":\"between\",\"end_year\":1965}}")
        val links = coupleLinks.map { if (it.geniId == "profile-1") it.copy(snapshot = ranged) else it }
        assertEquals(emptyList<UploadJob>(), plan(couple(), links).upserts) // Local "1960" is the start of the range
        val changed = plan(couple(ivanFields = ivan.copy(birth = EventFields("1961", null))), links).upserts
        assertEquals("1961", changed.single().params.toMap()["birth[date][year]"])
    }

    @Test
    fun rangeEditMakesAJobOnce() {
        val local = ivan.copy(birth = EventFields("BET 1960 AND 1965", null))
        val job = plan(couple(ivanFields = local)).upserts.single()
        assertEquals("between", job.params.toMap()["birth[date][range]"])
        val links = coupleLinks.map { if (it.geniId == "profile-1") it.copy(snapshot = SnapshotOverlay.apply(it.snapshot, job.params, "profile-1")) else it }
        assertEquals(emptyList<UploadJob>(), plan(couple(ivanFields = local), links).upserts)
    }

    @Test
    fun uploadedChangeIsNotPlannedAgain() {
        val changed = ivan.copy(occupation = "Driver", nicknames = "Vanya, Vano")
        val afterUpload = ivanJson.replace("\"Engineer\"", "\"Driver\", \"nicknames\": [\"Vanya\", \"Vano\"]")
        val links = coupleLinks.map { if (it.geniId == "profile-1") it.copy(snapshot = afterUpload) else it }
        val plan = plan(couple(ivanFields = changed), links, listOf(job("profile:profile-1")))
        assertEquals(emptyList<UploadJob>(), plan.upserts)
        assertTrue("profile:profile-1" in plan.deleteKeys)
    }

    @Test
    fun reusedGedcomIdIsNotLinkedToOldProfile() {
        // Ivan (I1) was deleted locally (an earlier save orphaned his link); a new person got the free ID I1 and has no tag
        val local = LocalTree(listOf(person("I1", null, newFields), person("I2", "profile-2", anna)), listOf(family("F1", "union-1", listOf("I1", "I2"))))
        val plan = plan(local, coupleLinks.map { if (it.geniId == "profile-1") it.copy(orphaned = true) else it })
        assertTrue(plan.upserts.none { it.key == "profile:profile-1" })
        assertEquals(listOf("profile-1"), plan.staleLinks)
        assertEquals(listOf("add:I1"), plan.upserts.map { it.key })
    }

    @Test
    fun lostTagWithoutSnapshotIsLinkedWithoutJob() {
        val links = coupleLinks.map { if (it.geniId == "profile-1") it.copy(snapshot = null) else it }
        val local = couple().let { it.copy(people = listOf(it.people[0].copy(geniId = null, fields = newFields)) + it.people.drop(1)) }
        val plan = plan(local, links)
        assertEquals(emptyList<UploadJob>(), plan.upserts)
        assertEquals(emptyList<String>(), plan.staleLinks)
    }

    @Test
    fun renamedTaglessPersonMakesNoJob() {
        // The tag was lost after an add and the person was renamed: maybe the same person, maybe a reused ID
        val local = couple().let { it.copy(people = listOf(it.people[0].copy(geniId = null, fields = newFields)) + it.people.drop(1)) }
        val plan = plan(local)
        assertEquals(emptyList<UploadJob>(), plan.upserts)
        assertEquals(emptyList<String>(), plan.staleLinks)
    }

    @Test
    fun absentRecordOrphansLink() {
        val links = coupleLinks + link("profile-9", "I9", annaJson) + link("union-9", "F9", unionJson)
        assertEquals(listOf("profile-9", "union-9"), plan(couple(), links).orphaned)
    }

    @Test
    fun presentTaggedRecordClearsOrphan() {
        val links = coupleLinks.map { if (it.geniId == "profile-1") it.copy(orphaned = true) else it }
        val plan = plan(couple(), links)
        assertEquals(listOf("profile-1"), plan.unorphaned)
        assertEquals(emptyList<String>(), plan.orphaned)
    }

    @Test
    fun reusedGedcomIdWithSameNameIsStillNew() {
        // Ivan was deleted; another Ivan User got the free ID I1. A name is no identity: never send him to Ivan's profile
        val local = couple().let { it.copy(people = listOf(it.people[0].copy(geniId = null, fields = ivan.copy(occupation = null))) + it.people.drop(1)) }
        val plan = plan(local, coupleLinks.map { if (it.geniId == "profile-1") it.copy(orphaned = true) else it })
        assertEquals(listOf("profile-1"), plan.staleLinks)
        assertTrue(plan.upserts.none { it.key == "profile:profile-1" })
        assertEquals(emptyList<String>(), plan.unorphaned)
    }

    @Test
    fun ambiguousRecordKeepsItsJobs() {
        val local = couple().let { it.copy(people = listOf(it.people[0].copy(geniId = null, fields = newFields)) + it.people.drop(1)) }
        assertFalse("profile:profile-1" in plan(local, jobs = listOf(job("profile:profile-1"))).deleteKeys)
    }

    @Test
    fun ambiguousPersonDropsOnlyItsPendingPhotoJobs() {
        val local = couple().let { it.copy(people = listOf(it.people[0].copy(geniId = null, fields = newFields, media = listOf(me))) + it.people.drop(1)) }
        for (state in JobState.entries) {
            val plan = plan(local, jobs = listOf(photoJob().copy(state = state)))
            assertEquals("$state", state == JobState.PENDING, "photo:I1:me.jpg" in plan.deleteKeys)
            assertEquals("$state", emptyList<UploadJob>(), plan.upserts.filter { it.kind == JobKind.ADD_PHOTO })
        }
    }

    @Test
    fun ambiguousPersonDropsLegacyPendingPhotoJobFoundByItsKey() {
        val local = couple().let { it.copy(people = listOf(it.people[0].copy(geniId = null, fields = newFields, media = listOf(me))) + it.people.drop(1)) }
        val legacy = photoJob().copy(personGedcomId = null)
        assertTrue("photo:I1:me.jpg" in plan(local, jobs = listOf(legacy)).deleteKeys)
        assertFalse("photo:I1:me.jpg" in plan(local, jobs = listOf(legacy.copy(state = JobState.FAILED))).deleteKeys)
    }

    @Test
    fun editedAddWithoutSnapshotFetchesSnapshotFirst() {
        // Added as New User; the snapshot could not be fetched, the link holds the hash of what was sent
        val olya = newFields.copy(given = "Olya")
        val links = coupleLinks + link("profile-3", "I3", null, newFields.hashCode())
        val plan = plan(couple(person("I3", "profile-3", olya)), links, listOf(job("profile:profile-3")))
        val job = plan.upserts.single()
        assertEquals("profile:profile-3", job.key)
        assertEquals(JobKind.UPDATE_PROFILE, job.kind)
        assertEquals(emptyList<Pair<String, String>>(), job.params) // Nothing sent before Geni's version is known
        assertFalse("profile:profile-3" in plan.deleteKeys)
        assertTrue(plan.verified.none { it.geniId == "profile-3" })
    }

    @Test
    fun unchangedAddWithoutSnapshotMakesNoJob() {
        val links = coupleLinks + link("profile-3", "I3", null, newFields.hashCode())
        assertEquals(emptyList<UploadJob>(), plan(couple(person("I3", "profile-3", newFields)), links).upserts)
    }

    @Test
    fun deleteJobSurvivesPlanningWhenItsRecordIsGone() {
        val delete = UploadJob(id = 9, treeId = 1, key = "delete:profile-9", kind = JobKind.DELETE_PROFILE, geniId = "profile-9",
            params = emptyList(), label = "x", localHash = 0)
        assertFalse("delete:profile-9" in plan(couple(), jobs = listOf(delete)).deleteKeys)
    }

    @Test
    fun deletePhotoJobIsNeverDroppedAsGone() {
        val delete = UploadJob(id = 9, treeId = 1, key = "delphoto:photo-9", kind = JobKind.DELETE_PHOTO, geniId = "photo-9",
            personGedcomId = "I1", params = emptyList(), label = "x", localHash = 0)
        assertFalse("delphoto:photo-9" in plan(couple(), jobs = listOf(delete)).deleteKeys)
    }

    private fun photoPlan(media: List<LocalMedia>, photoLinks: List<PhotoLink> = emptyList(), jobs: List<UploadJob> = emptyList(), uploadPhotos: Boolean = true, linked: Boolean = true): Plan {
        val owner = person("I1", "profile-1", ivan).copy(media = if (linked) media else emptyList())
        val stranger = person("I3", null, newFields).copy(media = media) // Not on Geni yet
        val local = LocalTree(listOf(owner, person("I2", "profile-2", anna)) + listOfNotNull(stranger.takeUnless { linked }),
            listOf(family("F1", "union-1", listOf("I1", "I2"))))
        return UploadPlanner.plan(1, local, coupleLinks, jobs, photoLinks, uploadPhotos)
    }
    private fun photoJob(key: String = "photo:I1:me.jpg") = UploadJob(id = 7, treeId = 1, key = key, kind = JobKind.ADD_PHOTO, geniId = "profile-1",
        personGedcomId = "I1", params = listOf("file" to "me.jpg"), label = "x", localHash = 0)
    private val me = LocalMedia("me.jpg", "Me", null)

    @Test
    fun newMediaOnLinkedPersonPlansPhotoJob() {
        val job = photoPlan(listOf(me)).upserts.single()
        assertEquals("photo:I1:me.jpg", job.key)
        assertEquals(JobKind.ADD_PHOTO, job.kind)
        assertEquals("profile-1", job.geniId)
        assertEquals("I1", job.personGedcomId)
        assertEquals(listOf("file" to "me.jpg", "title" to "Me"), job.params)
        assertEquals(me.hashCode(), job.localHash)
    }

    @Test
    fun taggedMediaIsNotPlanned() {
        assertEquals(emptyList<UploadJob>(), photoPlan(listOf(me.copy(geniPhotoId = "photo-1"))).upserts)
    }

    @Test
    fun mediaWithPhotoLinkIsNotPlanned() {
        val photoLink = PhotoLink(1, "profile-1", "photo-1", null, true, null, "me.jpg", PhotoState.DOWNLOADED)
        assertEquals(emptyList<UploadJob>(), photoPlan(listOf(me), listOf(photoLink)).upserts)
    }

    @Test
    fun photosOffPlansNothingAndDropsPendingJob() {
        val plan = photoPlan(listOf(me), jobs = listOf(photoJob()), uploadPhotos = false)
        assertEquals(emptyList<UploadJob>(), plan.upserts)
        assertTrue("photo:I1:me.jpg" in plan.deleteKeys)
    }

    @Test
    fun photosOffKeepsFailedAndDismissedJobsWhoseMediaStays() {
        for (state in listOf(JobState.FAILED, JobState.DISMISSED, JobState.CONFLICT, JobState.SENDING)) {
            val plan = photoPlan(listOf(me), jobs = listOf(photoJob().copy(state = state)), uploadPhotos = false)
            assertFalse("$state", "photo:I1:me.jpg" in plan.deleteKeys)
            assertEquals(emptyList<UploadJob>(), plan.upserts)
        }
    }

    @Test
    fun photosOffStillDropsFailedJobWhoseMediaIsGone() {
        assertTrue("photo:I1:me.jpg" in photoPlan(emptyList(), jobs = listOf(photoJob().copy(state = JobState.FAILED)), uploadPhotos = false).deleteKeys)
    }

    @Test
    fun photoJobLabelSaysItIsAPhoto() {
        assertEquals("Photo: me.jpg (Ivan User)", photoPlan(listOf(me)).upserts.single { it.kind == JobKind.ADD_PHOTO }.label)
    }

    @Test
    fun photoJobDroppedWhenMediaGone() {
        assertTrue("photo:I1:me.jpg" in photoPlan(emptyList(), jobs = listOf(photoJob())).deleteKeys)
    }

    @Test
    fun photoJobKeptWhileMediaStays() {
        assertFalse("photo:I1:me.jpg" in photoPlan(listOf(me), jobs = listOf(photoJob())).deleteKeys)
    }

    @Test
    fun mediaOfUnlinkedPersonIsNotPlanned() {
        assertEquals(emptyList<UploadJob>(), photoPlan(listOf(me), linked = false).upserts.filter { it.kind == JobKind.ADD_PHOTO })
    }

    @Test
    fun sameFileTwiceOnOnePersonPlansOneJob() {
        val upserts = photoPlan(listOf(me, me.copy(title = "Again"))).upserts.filter { it.kind == JobKind.ADD_PHOTO }
        assertEquals(listOf("photo:I1:me.jpg"), upserts.map { it.key })
    }

    @Test
    fun legacyCombinedNameDoesNotQueueANameWrite() {
        val maryJson = """{"id":"profile-1","first_name":"Mary Ann","middle_name":"Louise","last_name":"User","gender":"female",
            "is_alive":true,"occupation":"Engineer","display_name":"M. A. User"}"""
        // Saved before name parts and display names were stored
        val legacy = org.folg.gedcom.model.Person().apply {
            addName(org.folg.gedcom.model.Name().apply { value = "Mary Ann Louise /User/" })
            addEventFact(org.folg.gedcom.model.EventFact().apply { tag = "SEX"; value = "F" })
            addEventFact(org.folg.gedcom.model.EventFact().apply { tag = "OCCU"; value = "Driver" })
        }
        val links = coupleLinks.map { if (it.geniId == "profile-1") it.copy(snapshot = maryJson) else it }
        val job = plan(couple(ivanFields = ProfileFields.of(legacy)), links).upserts.single()
        assertEquals(listOf("occupation" to "Driver"), job.params)
    }
}
