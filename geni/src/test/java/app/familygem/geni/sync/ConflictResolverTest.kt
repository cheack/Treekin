package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.ProfileFields
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConflictResolverTest {

    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore().apply { save(AuthResult.Success("abc", Long.MAX_VALUE)) }
    private val store = InMemorySyncStore()
    private val host = FakeTreeHost()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private val snapshot = """{"id":"profile-1","first_name":"Ivan","occupation":"Engineer","birth":{"date":{"year":1960}}}"""
    private val geni = """{"id":"profile-1","first_name":"Ivan","occupation":"Pilot","birth":{"date":{"year":1961}}}"""

    /** Ivan edited his occupation to Driver; meanwhile Geni got Pilot and another birth year. */
    private val person = Person().apply {
        id = "I1"
        GeniMapper.applyProfile(Gedcom(), this, com.google.gson.Gson().fromJson(snapshot, app.familygem.geni.api.GeniProfile::class.java))
        eventsFacts.single { it.tag == "OCCU" }.value = "Driver"
        GeniMapper.setGeniId(this, "profile-1")
    }

    private suspend fun TestScope.setUpConflict(): Pair<ConflictResolver, UploadJob> {
        host.trees[1] = Gedcom().apply { addPerson(person) }
        store.saveLinks(listOf(GeniLink(1, "profile-1", "I1", true, null, snapshot, 0)))
        val client = GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime })
        val uploader = Uploader(client, store, host)
        val queue = SyncQueue(store, uploader)
        uploader.onTreeEdited = queue::setLocal
        queue.onSaved(1, LocalTree.capture(host.trees.getValue(1)))
        val job = store.jobs(1).single().copy(state = JobState.CONFLICT, error = "changed on Geni meanwhile: occupation")
        store.saveJob(job)
        return ConflictResolver(client, store, host, queue) to job
    }

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test
    fun detailsListOnlyConflictingGroups() = runTest {
        val (resolver, job) = setUpConflict()
        server.enqueue(ok(geni))
        assertEquals(listOf(FieldConflict("OCCUPATION", "Driver", "Pilot")), resolver.details(job))
    }

    @Test
    fun keepMineSendsMineOnly() = runTest {
        val (resolver, job) = setUpConflict()
        server.enqueue(ok(geni))
        resolver.resolve(job, keepMine = setOf("OCCUPATION"))
        assertEquals("Driver", person.eventsFacts.single { it.tag == "OCCU" }.value)
        assertEquals("1961", person.eventsFacts.single { it.tag == "BIRT" }.date) // Geni's other change is taken
        assertEquals(withNames(geni), store.link(1, "profile-1")!!.snapshot)
        val pending = store.jobs(1).single()
        assertEquals(JobState.PENDING, pending.state)
        assertEquals(listOf("occupation" to "Driver"), pending.params)
    }

    /** Two languages: de was changed here and on Geni, ru only on Geni. */
    private val localesBase = """{"id":"profile-1","first_name":"Ivan","names":{"en-US":{"first_name":"Ivan"},"de":{"first_name":"Iwan"},"ru":{"first_name":"Иван"}}}"""
    private val localesGeni = """{"id":"profile-1","first_name":"Ivan","names":{"en-US":{"first_name":"Ivan"},"de":{"first_name":"Iwan","last_name":"Geni"},"ru":{"first_name":"Ваня"}}}"""

    private suspend fun TestScope.setUpLocaleConflict(): Pair<ConflictResolver, UploadJob> {
        val gedcom = Gedcom()
        GeniMapper.applyProfile(gedcom, person, com.google.gson.Gson().fromJson(localesBase, app.familygem.geni.api.GeniProfile::class.java))
        person.eventsFacts.removeAll { it.tag == "OCCU" || it.tag == "BIRT" }
        app.familygem.geni.map.LocaleNames.set(person, "de", app.familygem.geni.map.LocaleName.of(mapOf("first_name" to "Iwan", "last_name" to "Mine")))
        host.trees[1] = Gedcom().apply { addPerson(person) }
        store.saveLinks(listOf(GeniLink(1, "profile-1", "I1", true, null, localesBase, 0)))
        val client = GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime })
        val uploader = Uploader(client, store, host)
        val queue = SyncQueue(store, uploader)
        uploader.onTreeEdited = queue::setLocal
        queue.onSaved(1, LocalTree.capture(host.trees.getValue(1)))
        val job = store.jobs(1).single().copy(state = JobState.CONFLICT, error = "changed on Geni meanwhile: LOCALE:de")
        store.saveJob(job)
        return ConflictResolver(client, store, host, queue) to job
    }

    @Test
    fun aLanguageConflictIsLabeledAndOnlyThatLanguageConflicts() = runTest {
        val (resolver, job) = setUpLocaleConflict()
        server.enqueue(ok(localesGeni))
        assertEquals(listOf(FieldConflict("LOCALE:de", "Iwan Mine", "Iwan Geni")), resolver.details(job))
    }

    @Test
    fun keepingMyLanguageSendsItAndTakesGenisOtherOne() = runTest {
        val (resolver, job) = setUpLocaleConflict()
        server.enqueue(ok(localesGeni))
        resolver.resolve(job, keepMine = setOf("LOCALE:de"), shown = setOf("LOCALE:de"))
        val fields = ProfileFields.of(person)
        assertEquals("Mine", fields.locales.getValue("de")["last_name"])
        assertEquals("Ваня", fields.locales.getValue("ru")["first_name"])
        assertEquals(listOf("names[de][last_name]" to "Mine"), store.jobs(1).single().params)
    }

    @Test
    fun takingGenisLanguageLeavesNothingToSend() = runTest {
        val (resolver, job) = setUpLocaleConflict()
        server.enqueue(ok(localesGeni))
        resolver.resolve(job, keepMine = emptySet(), shown = setOf("LOCALE:de"))
        assertEquals("Geni", ProfileFields.of(person).locales.getValue("de")["last_name"])
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun takeGeniAppliesGeniAndLeavesNothingToSend() = runTest {
        val (resolver, job) = setUpConflict()
        server.enqueue(ok(geni))
        resolver.resolve(job, keepMine = emptySet())
        assertEquals("Pilot", person.eventsFacts.single { it.tag == "OCCU" }.value)
        assertEquals(ProfileFields.ofSnapshot(geni), ProfileFields.of(person))
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun namesShowTheSplitWhenOnlyItDiffers() = runTest {
        person.names.single().value = "Ivan Petrovich"
        GeniMapper.setGivenNameParts(person, app.familygem.geni.map.GivenNameParts("Ivan", "Petrovich"))
        val (resolver, job) = setUpConflict()
        server.enqueue(ok(geni.replace("\"Ivan\"", "\"Ivan Petrovich\"")))
        assertEquals(FieldConflict("NAMES", "Ivan Petrovich (Ivan / Petrovich)", "Ivan Petrovich (Ivan Petrovich / —)"), resolver.details(job).first())
    }

    @Test
    fun namesAreShownAsOneName() = runTest {
        val (resolver, job) = setUpConflict()
        person.names.single().value = "Vanya /Petrov/"
        server.enqueue(ok(geni.replace("\"Ivan\"", "\"Ivan\",\"last_name\":\"Sidorov\"")))
        assertEquals(FieldConflict("NAMES", "Vanya Petrov", "Ivan Sidorov"), resolver.details(job).first())
    }

    @Test
    fun takingGeniKeepsOtherLocalEdits() = runTest {
        val (resolver, job) = setUpConflict()
        GeniMapper.setTag(person, GeniMapper.ABOUT_TAG, "Local") // Edited only here; Geni's text below differs only in form
        server.enqueue(ok(geni.replace("}", ""","about_me":""}""")))
        resolver.resolve(job, keepMine = emptySet())
        assertEquals("Local", GeniMapper.getTag(person, GeniMapper.ABOUT_TAG))
        assertEquals("Pilot", person.eventsFacts.single { it.tag == "OCCU" }.value)
    }

    @Test
    fun resolveRefusesWhenTheConflictsChangedSinceShown() = runTest {
        val (resolver, job) = setUpConflict()
        person.eventsFacts.single { it.tag == "BIRT" }.date = "1959" // A local birth edit: Geni changed birth too since the dialog
        server.enqueue(ok(geni))
        val error = runCatching { resolver.resolve(job, keepMine = emptySet(), shown = setOf("OCCUPATION")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("1959", person.eventsFacts.single { it.tag == "BIRT" }.date)
        assertEquals("Driver", person.eventsFacts.single { it.tag == "OCCU" }.value)
    }

    private val deleted = geni.replace("}", ""","deleted":true}""")

    @Test
    fun deletedProfileIsOneGoneConflict() = runTest {
        val (resolver, job) = setUpConflict()
        server.enqueue(ok(deleted))
        assertEquals(listOf(FieldConflict("GONE", "", "")), resolver.details(job))
    }

    @Test
    fun removingGonePersonDropsItFromTheTree() = runTest {
        val (resolver, job) = setUpConflict()
        server.enqueue(ok(deleted))
        resolver.resolve(job, keepMine = emptySet(), shown = setOf("GONE"))
        assertEquals(emptyList<Person>(), host.trees.getValue(1).people.orEmpty())
        assertEquals(null, store.link(1, "profile-1"))
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun keepingGonePersonLeavesALocalPerson() = runTest {
        val (resolver, job) = setUpConflict()
        server.enqueue(ok(deleted))
        resolver.resolve(job, keepMine = setOf("GONE"), shown = setOf("GONE"))
        assertEquals(listOf(person), host.trees.getValue(1).people)
        assertEquals("Driver", person.eventsFacts.single { it.tag == "OCCU" }.value)
        assertEquals(null, store.link(1, "profile-1"))
        assertEquals(emptyList<UploadJob>(), store.jobs(1)) // Never uploaded again: no link, no add for a tagged person
    }

    @Test
    fun fieldDialogDoesNotRemoveAPersonDeletedMeanwhile() = runTest {
        val (resolver, job) = setUpConflict()
        server.enqueue(ok(deleted))
        val error = runCatching { resolver.resolve(job, keepMine = emptySet(), shown = setOf("OCCUPATION")) }.exceptionOrNull()
        assertEquals("Geni changed again meanwhile; open the conflict again", error?.message)
        assertEquals(listOf(person), host.trees.getValue(1).people)
        assertEquals("I1", store.link(1, "profile-1")?.gedcomId)
    }

    @Test
    fun goneRemovalSparesAnotherPersonWithTheSameId() = runTest {
        val (resolver, job) = setUpConflict()
        app.familygem.geni.map.GeniMapper.setGeniId(person, "profile-2") // I1 now stands for someone else
        server.enqueue(ok(deleted))
        val error = runCatching { resolver.resolve(job, keepMine = emptySet(), shown = setOf("GONE")) }.exceptionOrNull()
        assertEquals("Geni changed again meanwhile; open the conflict again", error?.message)
        assertEquals(listOf(person), host.trees.getValue(1).people)
    }

    @Test
    fun goneRemovalRefusesTwoPeopleWithTheSameId() = runTest {
        val (resolver, job) = setUpConflict()
        val other = Person().apply { id = "I1" }
        host.trees.getValue(1).addPerson(other) // After the linked person
        server.enqueue(ok(deleted))
        val error = runCatching { resolver.resolve(job, keepMine = emptySet(), shown = setOf("GONE")) }.exceptionOrNull()
        assertEquals("Geni changed again meanwhile; open the conflict again", error?.message)
        assertEquals(listOf(person, other), host.trees.getValue(1).people)
    }
    /** The client adds an empty `names` to a stored profile that has none (Geni omits it). */
    private fun withNames(json: String) = json.removeSuffix("}") + ""","names":{}}"""
}
