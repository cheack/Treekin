package app.familygem.geni

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familygem.geni.map.GeniParams
import app.familygem.geni.map.ProfileFields
import app.familygem.geni.sync.PhotoScaler
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.folg.gedcom.parser.JsonParser as GedcomJsonParser

/**
 * Developer tool, not a feature: copies a local tree of the installed app into the Geni sandbox account
 * (test data for checks on a device). Skipped unless started with `-e geniImportTree <treeId>`, and only
 * against sandbox.geni.com. Progress goes to logcat (tag GeniImport). The gedcom id to Geni id map is kept in
 * files/geni-import-<treeId>.map, so a run after a failure continues instead of creating duplicates.
 *
 * `adb shell am instrument -w -e class app.familygem.geni.GeniImportTool -e geniImportTree 1
 *  net.cheack.treekin.test/androidx.test.runner.AndroidJUnitRunner`
 * With `-e geniCreateRoot profile-X` (no import) it makes a Geni tree rooted at that profile. With `-e geniCreateTree 1` it also makes a Geni tree rooted at the first imported person, to look at the copy in the app.
 */
@RunWith(AndroidJUnit4::class)
class GeniImportTool {

    private val profiles = mutableMapOf<String, String>() // person gedcom id to profile-X
    private val unions = mutableMapOf<String, String>() // family gedcom id to union-X

    @Test
    fun import() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val treeId = args.getString("geniImportTree")
        assumeTrue("no geniImportTree, geniCreateRoot or geniPhotosTree argument", treeId != null || args.getString("geniCreateRoot") != null || args.getString("geniPhotosTree") != null)
        check(Geni.config.host == "sandbox.geni.com") { "the tool only talks to the sandbox" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = Geni.client(context)
        args.getString("geniCreateRoot")?.let { root ->
            app.familygem.geni.GeniGlue.install()
            Log.i("GeniImport", "created tree ${Geni.loader(context).createTree(title = "Sandbox tree $root", rootGeniId = root)} rooted at $root")
            return@runBlocking
        }
        args.getString("geniPhotosTree")?.let { photoTree ->
            uploadPhotos(client, context, photoTree, args.getString("geniPhotosFrom")!!.toInt(), args.getString("geniPhotosCount")!!.toInt())
            return@runBlocking
        }
        // A tree that came from Geni is already there: importing it would only make copies
        check(Geni.store(context).tree(treeId!!.toInt()) == null) { "tree $treeId is a Geni tree" }
        val gedcom: Gedcom = GedcomJsonParser().fromJson(File(context.filesDir, "$treeId.json").readText())
        val mapFile = File(context.filesDir, "geni-import-$treeId.map")
        if (mapFile.exists()) mapFile.readLines().forEach {
            val (id, geni) = it.split(' ')
            (if (geni.startsWith("profile-")) profiles else unions)[id] = geni
        }
        fun remember(id: String, geni: String, into: MutableMap<String, String>) {
            into[id] = geni
            mapFile.appendText("$id $geni\n")
        }

        suspend fun create(path: String, personId: String): String {
            val fields = ProfileFields.of(gedcom.getPerson(personId))
            val params = GeniParams.profile(ProfileFields.EMPTY, fields, isNew = true)
            val json = JsonParser.parseString(client.post(path, params)).asJsonObject
            val id = json.get("id").asString
            Log.i("GeniImport", "$personId -> $id ($path)")
            remember(personId, id, profiles)
            // A new profile has the one union its creation made
            val union = json.getAsJsonArray("unions")?.firstOrNull()?.asString?.substringAfterLast('/').orEmpty()
            // Without the union id a later step would create a second union: stop instead
            check(path == "profile/add" || path.endsWith("/add-child") && path.startsWith("union-") || union.startsWith("union-")) {
                "no union in the answer of $path: ${json}"
            }
            return union
        }

        fun partners(f: Family) = f.husbandRefs.orEmpty().map { it.ref } + f.wifeRefs.orEmpty().map { it.ref }
        fun union(path: String) = path.substringAfterLast('/')

        if (profiles.isEmpty()) {
            val root = gedcom.people.first().id
            create("profile/add", root)
        }
        var progress = true
        while (progress) {
            progress = false
            for (f in gedcom.families) {
                val spouses = partners(f)
                val children = f.childRefs.orEmpty().map { it.ref }
                val unionId = unions[f.id]
                if (unionId != null) {
                    for (s in spouses.filter { it !in profiles }) {
                        create("$unionId/add-partner", s); progress = true
                    }
                    for (c in children.filter { it !in profiles }) {
                        create("$unionId/add-child", c); progress = true
                    }
                    continue
                }
                val knownSpouse = spouses.firstOrNull { it in profiles }
                val knownChild = children.firstOrNull { it in profiles }
                if (knownSpouse != null) {
                    val other = spouses.firstOrNull { it !in profiles }
                    val newUnion = if (other != null) create("${profiles[knownSpouse]}/add-partner", other)
                    else if (children.isNotEmpty()) {
                        // Single parent: the first child makes the union
                        val first = children.first()
                        if (first in profiles) "" else create("${profiles[knownSpouse]}/add-child", first)
                    } else ""
                    if (newUnion.isNotEmpty()) {
                        remember(f.id, union(newUnion), unions); progress = true
                    } else if (other != null) progress = true
                } else if (knownChild != null && spouses.isNotEmpty()) {
                    val newUnion = create("${profiles[knownChild]}/add-parent", spouses.first())
                    if (newUnion.isNotEmpty()) remember(f.id, union(newUnion), unions)
                    progress = true
                }
            }
        }
        val missing = gedcom.people.map { it.id }.filter { it !in profiles }
        Log.i("GeniImport", "done: ${profiles.size} profiles, ${unions.size} unions, missing: $missing")
        if (args.getString("geniCreateTree") != null) {
            app.familygem.geni.GeniGlue.install()
            val rootGeniId = profiles.getValue(gedcom.people.first().id)
            val created = Geni.loader(context).createTree(title = "Imported tree $treeId", rootGeniId = rootGeniId)
            Log.i("GeniImport", "created tree $created rooted at $rootGeniId")
        }
        Unit
    }

    /**
     * Uploads the main photo of each person of a local tree to the sandbox profile of the same name, looking at the
     * [count] profiles from `profile-<from>` (what an earlier import created). Photos are read from the tree's
     * external media folder. Profiles that already have a photo are skipped.
     * The saved import map (`geni-import-<treeId>.map`) is used when there is one; without it (data was cleared) profiles are
     * matched by first and last name, so people sharing a name may be skipped or swapped: a fallback for test data only.
     */
    private suspend fun uploadPhotos(client: app.familygem.geni.api.GeniClient, context: android.content.Context, treeId: String, from: Int, count: Int) {
        val gedcom: Gedcom = GedcomJsonParser().fromJson(File(context.filesDir, "$treeId.json").readText())
        val dir = File(context.getExternalFilesDir(null), treeId)
        val byName = mutableMapOf<String, String>()
        (from until from + count).map { "profile-$it" }.chunked(20).forEach { ids ->
            client.profiles(ids).forEach { (id, pair) -> byName.putIfAbsent("${pair.first.firstName} ${pair.first.lastName}", id) }
        }
        val saved = File(context.filesDir, "geni-import-$treeId.map").takeIf { it.exists() }?.readLines().orEmpty()
            .map { it.split(' ') }.filter { it[1].startsWith("profile-") }.associate { it[0] to it[1] }
        var uploaded = 0
        for (person in gedcom.people) {
            val fields = ProfileFields.of(person)
            val key = "${fields.given?.substringBefore(' ')} ${fields.surname}"
            val id = saved[person.id] ?: byName.remove(key) ?: continue
            val media = person.media.orEmpty().firstOrNull { it.file != null } ?: continue
            val file = File(dir, media.file.substringAfterLast('/'))
            if (!file.exists() || client.photos(id).isNotEmpty()) continue
            client.addPhoto(id, file.name, media.title, PhotoScaler.scaled(file))
            uploaded++
            Log.i("GeniImport", "photo ${file.name} -> $id")
        }
        Log.i("GeniImport", "photos done: $uploaded uploaded")
    }
}
