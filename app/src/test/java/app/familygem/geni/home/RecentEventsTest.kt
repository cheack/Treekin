package app.familygem.geni.home

import app.familygem.geni.home.FeedEvent.Kind.BIRTHDAY
import app.familygem.geni.home.FeedEvent.Kind.PROFILE_UPDATED
import org.folg.gedcom.model.Change
import org.folg.gedcom.model.DateTime
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class RecentEventsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Rome")

    private fun at(date: String, time: String = "12:00"): Instant =
        ZonedDateTime.of(LocalDate.parse(date), java.time.LocalTime.parse(time), zone).toInstant()

    private fun person(id: String, birth: String? = null, dead: String? = null, change: String? = null, changeTime: String? = null, changeZone: String? = null) =
        Person().apply {
            this.id = id
            birth?.let { addEventFact(EventFact().apply { tag = "BIRT"; date = it }) }
            dead?.let { addEventFact(EventFact().apply { tag = it }) }
            change?.let {
                this.change = Change().apply {
                    dateTime = DateTime().apply { value = it; time = changeTime }
                    changeZone?.let { z -> putExtension("zone", z) }
                }
            }
        }

    private fun build(vararg people: Person, now: Instant = at("2026-11-25"), lifeSpan: Int = 120) =
        RecentEvents.build(people.toList(), lifeSpan, now, zone)

    @Test
    fun `upcoming birthdays soonest first with the age reached`() {
        val feed = build(person("I2", "2 DEC 1980"), person("I1", "28 NOV 1990"))
        assertEquals(listOf("I1" to 36, "I2" to 46), feed.upcoming.map { it.personId to it.age })
        assertEquals(listOf(LocalDate.of(2026, 11, 28), LocalDate.of(2026, 12, 2)), feed.upcoming.map { it.date })
    }

    @Test
    fun `today's birthday appears once under today`() {
        val feed = build(person("I1", "25 NOV 1970"))
        assertTrue(feed.upcoming.isEmpty())
        assertEquals(1, feed.recent.size)
        val (date, events) = feed.recent[0]
        assertEquals(LocalDate.of(2026, 11, 25), date)
        assertEquals(listOf(FeedEvent(BIRTHDAY, "I1", date, 56)), events)
    }

    @Test
    fun `window boundaries`() {
        val feed = build(
            person("in14", "9 DEC 1980"), person("out15", "10 DEC 1980"),
            person("in90", "27 AUG 1980"), person("out91", "26 AUG 1980")
        )
        assertEquals(listOf("in14"), feed.upcoming.map { it.personId })
        assertEquals(listOf("in90"), feed.recent.flatMap { it.second }.map { it.personId })
    }

    @Test
    fun `december to january rollover`() {
        val feed = build(person("I1", "3 JAN 2000"), now = at("2026-12-25"))
        assertEquals(listOf(LocalDate.of(2027, 1, 3) to 27), feed.upcoming.map { it.date to it.age })
        // Past birthdays of the previous year
        val past = build(person("I2", "20 DEC 2000"), now = at("2027-01-05"))
        assertEquals(listOf(LocalDate.of(2026, 12, 20)), past.recent.map { it.first })
        assertEquals(26, past.recent[0].second[0].age)
    }

    @Test
    fun `february 29 falls on february 28 in non leap years`() {
        val feed = build(person("I1", "29 FEB 2000"), now = at("2027-02-20"))
        assertEquals(listOf(LocalDate.of(2027, 2, 28) to 27), feed.upcoming.map { it.date to it.age })
        val leap = build(person("I1", "29 FEB 2000"), now = at("2028-02-20"))
        assertEquals(listOf(LocalDate.of(2028, 2, 29)), leap.upcoming.map { it.date })
    }

    @Test
    fun `deceased incomplete and beyond lifespan are skipped`() {
        val feed = build(
            person("dead", "28 NOV 1950", dead = "DEAT"), person("buried", "28 NOV 1950", dead = "BURI"),
            person("noDay", "NOV 1950"), person("noYear", "28 NOV"), person("about", "ABT 28 NOV 1950"),
            person("range", "BET 1 NOV 1950 AND 28 NOV 1950"), person("garbage", "soon"),
            person("old", "28 NOV 1900"), person("ok", "28 NOV 1950"), lifeSpan = 100
        )
        // As in Notifier, an approximate date with day, month and year counts as a single date
        assertEquals(listOf("about", "ok"), feed.upcoming.map { it.personId })
        assertTrue(feed.recent.isEmpty())
    }

    @Test
    fun `day of birth is not a birthday`() {
        assertTrue(build(person("baby", "28 NOV 2026")).isEmpty)
    }

    @Test
    fun `profile updates grouped with birthdays and ordered`() {
        val feed = build(
            person("I3", change = "20 Nov 2026", changeTime = "08:00:00"),
            person("I2", change = "20 NOV 2026", changeTime = "18:30:00"),
            person("I1", "20 NOV 1980"),
            person("I4", change = "25 Nov 2026", changeTime = "09:00:00")
        )
        assertEquals(listOf(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 20)), feed.recent.map { it.first })
        assertEquals(listOf(BIRTHDAY to "I1", PROFILE_UPDATED to "I2", PROFILE_UPDATED to "I3"),
            feed.recent[1].second.map { it.kind to it.personId })
    }

    @Test
    fun `birthday and update of the same person both appear`() {
        val feed = build(person("I1", "25 NOV 1980", change = "25 Nov 2026", changeTime = "10:00:00"))
        assertEquals(listOf(BIRTHDAY, PROFILE_UPDATED), feed.recent.single().second.map { it.kind })
    }

    @Test
    fun `malformed future and old change dates are skipped`() {
        val feed = build(
            person("bad", change = "32 Nov 2026"), person("word", change = "yesterday"),
            person("month", change = "20 FOO 2026"), person("future", change = "26 Nov 2026"),
            person("laterToday", change = "25 Nov 2026", changeTime = "13:00:00"),
            person("old", change = "26 Aug 2026"), person("edge", change = "27 Aug 2026"),
            person("noTime", change = "24 Nov 2026"), person("badTime", change = "24 Nov 2026", changeTime = "25:99")
        )
        assertEquals(listOf("noTime", "badTime", "edge").sorted(), feed.recent.flatMap { it.second }.map { it.personId }.sorted())
    }

    @Test
    fun `stored zone of the change is used`() {
        // 23:30 in New York on 24 Nov is 05:30 of 25 Nov in Rome
        val feed = build(person("I1", change = "24 Nov 2026", changeTime = "23:30:00", changeZone = "America/New_York"))
        assertEquals(LocalDate.of(2026, 11, 25), feed.recent.single().first)
        // Unknown zone: the device one
        val fallback = build(person("I1", change = "24 Nov 2026", changeTime = "23:30:00", changeZone = "Nowhere/City"))
        assertEquals(LocalDate.of(2026, 11, 24), fallback.recent.single().first)
    }

    @Test
    fun `change in the future of the device clock is skipped even today`() {
        val feed = build(person("I1", change = "25 Nov 2026", changeTime = "11:00:00", changeZone = "America/New_York"))
        assertTrue(feed.isEmpty) // 11:00 in New York is 17:00 in Rome, after the 12:00 now
    }

    @Test
    fun `time parsing`() {
        assertEquals(java.time.LocalTime.of(14, 3, 22), RecentEvents.parseTime("14:03:22.5"))
        assertEquals(java.time.LocalTime.of(9, 5), RecentEvents.parseTime("9:05"))
        assertNull(RecentEvents.parseTime("noon"))
    }
}
