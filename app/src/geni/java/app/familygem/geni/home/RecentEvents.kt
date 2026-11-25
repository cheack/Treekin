package app.familygem.geni.home

import app.familygem.DateConverter
import app.familygem.constant.Format
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.sync.FeedRecord
import app.familygem.geni.sync.FeedRecordKind
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Person
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One row of the Home feed. [years] is the age reached (birthday), the years of marriage (anniversary)
 * or the years since the death (memorial day) on [date].
 */
data class FeedEvent(
    val kind: Kind,
    /** The person of the event; for a wedding anniversary the first partner; for a removal the Geni profile, since the person is gone. */
    val personId: String,
    val date: LocalDate,
    val years: Int = 0,
    /** When the profile was changed (profile updates only): orders the updates of one day, newest first. */
    val changedAt: Instant? = null,
    /** Wedding anniversaries only: the family and the second partner. */
    val familyId: String? = null,
    val partnerId: String? = null,
    /** Removals only: the name the person had when it left the tree. */
    val name: String? = null
) {
    /** In the order they are listed within one day. */
    enum class Kind {
        BIRTHDAY, WEDDING_ANNIVERSARY, MEMORIAL, PROFILE_ADDED, PROFILE_UPDATED, GONE_ON_GENI, DELETED_ON_GENI, REMOVED_FROM_DEVICE;

        /** The person has left the tree: the row has only a name. */
        val isRemoval get() = this == GONE_ON_GENI || this == DELETED_ON_GENI || this == REMOVED_FROM_DEVICE
    }
}

/** Upcoming yearly events (soonest first) and the recent events grouped by day (newest day first, today included). */
data class Feed(val upcoming: List<FeedEvent>, val recent: List<Pair<LocalDate, List<FeedEvent>>>) {
    val isEmpty get() = upcoming.isEmpty() && recent.isEmpty()
}

/**
 * Builds the Home feed from the people and families of the local tree: no Geni request, no write to the tree.
 * Pure function of its inputs, so the date and the time zone are given and not read from the device.
 */
object RecentEvents {

    /** Days after today with upcoming events. */
    const val UPCOMING_DAYS = 14L

    /** Days before today with recent events (today is shown too). */
    const val RECENT_DAYS = 90L

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /**
     * @param records What the sync recorded and the tree cannot tell: profiles created on Geni, people who left the tree
     */
    fun build(
        people: List<Person>, families: List<Family>, lifeSpan: Int, now: Instant, zone: ZoneId, records: List<FeedRecord> = emptyList()
    ): Feed {
        val today = now.atZone(zone).toLocalDate()
        val upcomingEnd = today.plusDays(UPCOMING_DAYS)
        val recentStart = today.minusDays(RECENT_DAYS)
        val upcoming = mutableListOf<FeedEvent>()
        val recent = mutableListOf<FeedEvent>()

        /** Adds the yearly returns of [origin] that fall in the windows, from the first one up to [lifeSpan] years. */
        fun yearly(origin: LocalDate, event: (date: LocalDate, years: Int) -> FeedEvent) {
            // The windows span at most a year boundary, so the previous, current and next year cover them
            for (year in today.year - 1..today.year + 1) {
                val date = anniversary(origin, year)
                val years = year - origin.year
                if (years < 1 || years > lifeSpan) continue // The day itself is not a return; the tree's lifespan limits the rest
                if (date.isAfter(today) && !date.isAfter(upcomingEnd)) upcoming += event(date, years)
                else if (!date.isAfter(today) && !date.isBefore(recentStart)) recent += event(date, years)
            }
        }

        val living = mutableMapOf<String, Person>()
        for (person in people) {
            val id = person.id ?: continue
            if (!isDead(person)) living[id] = person
            birthDate(person)?.let { birth -> yearly(birth) { date, years -> FeedEvent(FeedEvent.Kind.BIRTHDAY, id, date, years) } }
            deathDate(person)?.let { death -> yearly(death) { date, years -> FeedEvent(FeedEvent.Kind.MEMORIAL, id, date, years) } }
            changeInstant(person, zone)?.let { changedAt ->
                if (changedAt.isAfter(now)) return@let // A clock set wrong somewhere: not a recent change
                val date = changedAt.atZone(zone).toLocalDate()
                if (!date.isBefore(recentStart)) recent += FeedEvent(FeedEvent.Kind.PROFILE_UPDATED, id, date, changedAt = changedAt)
            }
        }
        for (family in families) {
            val familyId = family.id ?: continue
            val marriage = marriageDate(family) ?: continue
            // Only a couple still together: two living partners, no divorce or annulment
            val partners = (family.husbandRefs.orEmpty() + family.wifeRefs.orEmpty()).mapNotNull { it.ref }
            if (partners.size != 2 || partners.any { it !in living }) continue
            yearly(marriage) { date, years ->
                FeedEvent(FeedEvent.Kind.WEDDING_ANNIVERSARY, partners[0], date, years, familyId = familyId, partnerId = partners[1])
            }
        }
        val byId = people.associateBy { it.id }
        for (record in records) {
            val at = Instant.ofEpochMilli(record.atMillis)
            if (at.isAfter(now)) continue
            val date = at.atZone(zone).toLocalDate()
            if (date.isBefore(recentStart)) continue
            recent += when (record.kind) {
                FeedRecordKind.ADDED -> {
                    // Only while the person is still in the tree under that profile (its GEDCOM ID may have been reused)
                    val person = record.gedcomId?.let { byId[it] } ?: continue
                    if (GeniMapper.getGeniId(person) != record.geniId) continue
                    FeedEvent(FeedEvent.Kind.PROFILE_ADDED, person.id, date, changedAt = at)
                }
                FeedRecordKind.GONE_ON_GENI -> FeedEvent(FeedEvent.Kind.GONE_ON_GENI, record.geniId, date, changedAt = at, name = record.name)
                FeedRecordKind.DELETED_ON_GENI -> FeedEvent(FeedEvent.Kind.DELETED_ON_GENI, record.geniId, date, changedAt = at, name = record.name)
                FeedRecordKind.REMOVED_FROM_DEVICE ->
                    FeedEvent(FeedEvent.Kind.REMOVED_FROM_DEVICE, record.geniId, date, changedAt = at, name = record.name)
            }
        }
        upcoming.sortWith(compareBy<FeedEvent> { it.date }.thenBy { it.kind }.thenBy { it.personId })
        val groups = recent.groupBy { it.date }.toSortedMap(reverseOrder()).map { (date, events) ->
            date to events.sortedWith(
                compareBy<FeedEvent> { it.kind }.thenByDescending { it.changedAt }.thenBy { it.personId }
            )
        }
        return Feed(upcoming, groups)
    }

    private fun isDead(person: Person) = person.eventsFacts.orEmpty().any { it.tag == "DEAT" || it.tag == "BURI" }

    /** The date of the first [tag] fact with a date, if it is a single date with day, month and year (Notifier's rule). */
    private fun exactDate(facts: List<EventFact>?, tag: String): LocalDate? {
        val fact = facts.orEmpty().firstOrNull { it.tag == tag && it.date != null } ?: return null
        val converter = DateConverter(fact.date)
        if (!converter.isSingleKind || converter.firstDate.format != Format.D_M_Y) return null
        val date = converter.firstDate.date ?: return null
        return LocalDate.of(date.year, date.monthOfYear, date.dayOfMonth)
    }

    /** The birth date used for birthdays, as Notifier.findBirth: a living person (no death or burial fact) with an exact birth date. */
    fun birthDate(person: Person): LocalDate? = if (isDead(person)) null else exactDate(person.eventsFacts, "BIRT")

    /** The exact death date, for memorial days. */
    fun deathDate(person: Person): LocalDate? = exactDate(person.eventsFacts, "DEAT")

    /** The exact marriage date of a family not divorced or annulled. */
    fun marriageDate(family: Family): LocalDate? {
        if (family.eventsFacts.orEmpty().any { it.tag == "DIV" || it.tag == "ANUL" }) return null
        return exactDate(family.eventsFacts, "MARR")
    }

    /** The return of [origin] in [year]: February 29 falls on February 28 in the other years. */
    fun anniversary(origin: LocalDate, year: Int): LocalDate = origin.withYear(year)

    /**
     * When the person's record was last changed (GEDCOM CHAN), in the zone stored with it or else [fallbackZone].
     * Null when there is no change date or it cannot be read. A missing or unreadable time counts as the start of the day.
     */
    fun changeInstant(person: Person, fallbackZone: ZoneId): Instant? {
        val change = person.change ?: return null
        val date = parseDate(change.dateTime?.value) ?: return null
        val time = parseTime(change.dateTime?.time) ?: LocalTime.MIDNIGHT
        val zone = (change.getExtension("zone") as? String)?.let {
            try {
                ZoneId.of(it)
            } catch (e: Exception) {
                null
            }
        } ?: fallbackZone
        return ZonedDateTime.of(date, time, zone).toInstant()
    }

    /** Reads a GEDCOM exact date like "5 NOV 2026" (also "5 Nov 2026", as ChangeUtil writes it). */
    internal fun parseDate(value: String?): LocalDate? {
        val parts = value?.trim()?.split(Regex("\\s+")) ?: return null
        if (parts.size != 3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = MONTHS.indexOf(parts[1].uppercase()) + 1
        val year = parts[2].toIntOrNull() ?: return null
        if (month == 0) return null
        return try {
            LocalDate.of(year, month, day)
        } catch (e: Exception) {
            null
        }
    }

    /** Reads a GEDCOM time like "14:03", "14:03:22" or "14:03:22.5". */
    internal fun parseTime(value: String?): LocalTime? {
        val match = Regex("""(\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?""").matchEntire(value?.trim() ?: return null) ?: return null
        val (hour, minute, second) = match.destructured
        return try {
            LocalTime.of(hour.toInt(), minute.toInt(), second.ifEmpty { "0" }.toInt())
        } catch (e: Exception) {
            null
        }
    }
}
