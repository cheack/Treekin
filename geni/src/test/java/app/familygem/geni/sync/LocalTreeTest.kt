package app.familygem.geni.sync

import app.familygem.geni.map.GeniMapper
import org.folg.gedcom.model.ChildRef
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Media
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SpouseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTreeTest {

    private fun person(id: String, name: String?) = Person().apply {
        this.id = id
        if (name != null) addName(Name().apply { value = name })
    }

    @Test
    fun captureListsPartnersAndChildren() {
        val gedcom = Gedcom()
        gedcom.addPerson(person("I1", "Ivan /User/").also { GeniMapper.setGeniId(it, "profile-1") })
        gedcom.addPerson(person("I2", "Maria /User/"))
        gedcom.addPerson(person("I3", null))
        gedcom.addFamily(Family().apply {
            id = "F1"
            addWife(SpouseRef().apply { ref = "I2" })
            addHusband(SpouseRef().apply { ref = "I1" })
            addChild(ChildRef().apply { ref = "I3" })
            GeniMapper.setGeniId(this, "union-1")
        })
        val local = LocalTree.capture(gedcom)
        assertEquals(listOf("I1", "I2", "I3"), local.people.map { it.gedcomId })
        assertEquals("profile-1", local.people[0].geniId)
        assertNull(local.people[1].geniId)
        assertEquals("Ivan User", local.people[0].label)
        assertEquals("I3", local.people[2].label)
        assertEquals("Ivan", local.people[0].fields.given)
        val family = local.families.single()
        assertEquals("union-1", family.geniId)
        assertEquals(listOf("I1", "I2"), family.partners)
        assertEquals(listOf("I3"), family.children)
    }

    @Test
    fun captureTakesLineageOfEachChildInItsFamily() {
        val gedcom = Gedcom()
        listOf("I1", "I2", "I3", "I4").forEach { gedcom.addPerson(person(it, null)) }
        fun ref(family: String, type: String?) = org.folg.gedcom.model.ParentFamilyRef().apply { ref = family; relationshipType = type }
        gedcom.people.single { it.id == "I2" }.addParentFamilyRef(ref("F1", "adopted"))
        gedcom.people.single { it.id == "I3" }.addParentFamilyRef(ref("F1", "foster"))
        gedcom.people.single { it.id == "I4" }.apply { addParentFamilyRef(ref("F1", "birth")); addParentFamilyRef(ref("F2", "adopted")) }
        gedcom.addFamily(Family().apply { id = "F1"; listOf("I2", "I3", "I4").forEach { c -> addChild(ChildRef().apply { ref = c }) } })
        gedcom.addFamily(Family().apply { id = "F2"; addChild(ChildRef().apply { ref = "I4" }) })
        val local = LocalTree.capture(gedcom)
        assertEquals(mapOf("I2" to "adopt", "I3" to "foster"), local.families[0].lineages)
        assertEquals(mapOf("I4" to "adopt"), local.families[1].lineages)
    }

    @Test
    fun captureSkipsMediaThatIsAnUrl() {
        val gedcom = Gedcom()
        gedcom.addPerson(person("I1", null).apply {
            addMedia(Media().apply { file = "https://example.com/a.jpg" })
            addMedia(Media().apply { file = "me.jpg" })
        })
        assertEquals(listOf("me.jpg"), LocalTree.capture(gedcom).people.single().media.map { it.file })
    }

    @Test
    fun captureSkipsMediaWithAPathAndNonImages() {
        val gedcom = Gedcom()
        gedcom.addPerson(person("I1", null).apply {
            addMedia(Media().apply { file = "/storage/me.jpg" })
            addMedia(Media().apply { file = "dir\\me.jpg" })
            addMedia(Media().apply { file = "doc.pdf" })
            addMedia(Media().apply { file = "noext" })
            addMedia(Media().apply { file = "Upper.JPG" })
        })
        assertEquals(listOf("Upper.JPG"), LocalTree.capture(gedcom).people.single().media.map { it.file })
    }
}
