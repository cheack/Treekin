package app.familygem.geni.map

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Geni's version of a record after an accepted write, built from the form parameters that were sent:
 * the base used for comparisons when Geni's full version could not be fetched after the write.
 */
object SnapshotOverlay {

    private val NUMBERS = setOf("day", "month", "year", "end_day", "end_month", "end_year")
    private val BOOLEANS = setOf("is_alive", "circa", "end_circa")

    /** [base] (raw Geni JSON, or nothing for a new record) with [params] written over it, as Geni stores them. */
    fun apply(base: String?, params: List<Pair<String, String>>, id: String): String {
        val root = base?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() } ?: JsonObject()
        root.addProperty("id", id)
        for ((key, value) in params) {
            val path = key.replace("]", "").split('[')
            var parent = root
            for (name in path.dropLast(1)) parent = parent.getAsJsonObject(name) ?: JsonObject().also { parent.add(name, it) }
            val leaf = path.last()
            if (value.isEmpty()) parent.remove(leaf) else parent.add(leaf, element(leaf, value))
        }
        // Geni ignores death for a living profile, and no death parameters are sent with is_alive=true
        if (root.get("is_alive")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
            root.remove("death")
            root.remove("cause_of_death")
        }
        prune(root)
        return root.toString()
    }

    private fun element(name: String, value: String) = when (name) {
        in NUMBERS -> value.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
        in BOOLEANS -> JsonPrimitive(value.toBoolean())
        "nicknames" -> JsonArray().apply { value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) } }
        else -> JsonPrimitive(value)
    }

    /** Drops objects left empty (an event whose date and place were both cleared). */
    private fun prune(obj: JsonObject) {
        for (name in obj.keySet().toList()) {
            val child = obj.get(name)
            if (child is JsonObject) {
                prune(child)
                if (child.size() == 0) obj.remove(name)
            }
        }
    }
}
