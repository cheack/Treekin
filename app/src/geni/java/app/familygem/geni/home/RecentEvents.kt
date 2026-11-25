package app.familygem.geni.home

import app.familygem.DateConverter
import app.familygem.constant.Format
import org.folg.gedcom.model.Person
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One row of the Home feed. [age] is the age reached on [date] (birthdays only). */
data class FeedEvent(
    val kind: Kind,
    val personId: String,
    val date: LocalDate,
    val age: Int = 0,
    /** When the profile was changed (profile updates only): orders the updates of one day, newest first. */
    val changedAt: Instant? = null
) {
    enum class Kind { BIRTHDAY, PROFILE_UPDATED }
}

/** Upcoming birthdays (soonest first) and the recent events grouped by day (newest day first, today included). */
data class Feed(val upcoming: List<FeedEvent>, val recent: List<Pair<LocalDate, List<FeedEvent>>>) {
    val isEmpty get() = upcoming.isEmpty() && recent.isEmpty()
}

/**
 * Builds the Home feed from the people of the local tree: no Geni request, no write to the tree.
 * Pure function of its inputs, so the date and the time zone are given and not read from the device.
 */
object RecentEvents {

    /** Days after today with upcoming birthdays. */
    const val UPCOMING_DAYS = 14L

    /** Days before today with recent events (today is shown too). */
    const val RECENT_DAYS = 90L

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    fun build(people: List<Person>, lifeSpan: Int, now: Instant, zone: ZoneId): Feed {
        val today = now.atZone(zone).toLocalDate()
        val upcomingEnd = today.plusDays(UPCOMING_DAYS)
        val recentStart = today.minusDays(RECENT_DAYS)
        val upcoming = mutableListOf<FeedEvent>()
        val recent = mutableListOf<FeedEvent>()
        for (person in people) {
            val id = person.id ?: continue
            birthDate(person)?.let { birth ->
                // The windows span at most a year boundary, so the previous, current and next year cover them
                for (year in today.year - 1..today.year + 1) {
                    val date = birthday(birth, year)
                    val age = year - birth.year
                    if (age < 1 || age > lifeSpan) continue // Day of birth is not a birthday; the tree's lifespan limits the rest
                    val event = FeedEvent(FeedEvent.Kind.BIRTHDAY, id, date, age)
                    if (date.isAfter(today) && !date.isAfter(upcomingEnd)) upcoming += event
                    else if (!date.isAfter(today) && !date.isBefore(recentStart)) recent += event
                }
            }
            changeInstant(person, zone)?.let { changedAt ->
                if (changedAt.isAfter(now)) return@let // A clock set wrong somewhere: not a recent change
                val date = changedAt.atZone(zone).toLocalDate()
                if (!date.isBefore(recentStart)) recent += FeedEvent(FeedEvent.Kind.PROFILE_UPDATED, id, date, changedAt = changedAt)
            }
        }
        upcoming.sortWith(compareBy<FeedEvent> { it.date }.thenBy { it.personId })
        val groups = recent.groupBy { it.date }.toSortedMap(reverseOrder()).map { (date, events) ->
            date to events.sortedWith(
                compareBy<FeedEvent> { it.kind }.thenByDescending { it.changedAt }.thenBy { it.personId }
            )
        }
        return Feed(upcoming, groups)
    }

    /**
     * The birth date used for birthdays, as Notifier.findBirth: a living person (no death or burial fact)
     * whose first birth with a date has a single date with day, month and year.
     */
    fun birthDate(person: Person): LocalDate? {
        val facts = person.eventsFacts ?: return null
        if (facts.any { it.tag == "DEAT" || it.tag == "BURI" }) return null
        val birth = facts.firstOrNull { it.tag == "BIRT" && it.date != null } ?: return null
        val converter = DateConverter(birth.date)
        if (!converter.isSingleKind || converter.firstDate.format != Format.D_M_Y) return null
        val date = converter.firstDate.date ?: return null
        return LocalDate.of(date.year, date.monthOfYear, date.dayOfMonth)
    }

    /** The birthday of [year]: February 29 falls on February 28 in the other years. */
    fun birthday(birth: LocalDate, year: Int): LocalDate = birth.withYear(year)

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
