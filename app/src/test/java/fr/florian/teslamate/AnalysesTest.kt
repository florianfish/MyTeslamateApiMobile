package fr.florian.teslamate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trajets types et pertes à l'arrêt (onglet Stats). */
class AnalysesTest {

    // ---- Trajets récurrents ----

    @Test
    fun `trajet type fait au moins trois fois`() {
        val routes = Fixtures.drives.routes()
        assertEquals(1, routes.size)
        val r = routes.single()
        assertEquals(HOME, r.from)
        assertEquals(PR, r.to)
        assertEquals(setOf(5655, 5619, 5609), r.drives.map { it.id }.toSet())
        assertEquals(6.940339000000677, r.avgKm, 1e-9)
        assertEquals(49 / 3.0, r.avgMin, 1e-9)
        assertEquals(143.18307314189835, r.avgConso!!, 1e-9)
        assertEquals(5609, r.best!!.id)
        assertEquals(5619, r.worst!!.id)
    }

    @Test
    fun `trajets types tries par frequence`() {
        val routes = Fixtures.drives.routes(min = 2)
        assertEquals(listOf(HOME to PR, PR to HOME), routes.map { it.from to it.to })
        assertEquals(setOf(5620, 5610), routes[1].drives.map { it.id }.toSet())
    }

    @Test
    fun `adresses comparees a l'identique`() {
        // « Parc relais du Nord » (5661) reste un trajet à part
        val r = Fixtures.drives.routes(min = 1).single { it.to == "Parc relais du Nord, Villeneuve" }
        assertEquals(listOf(5661), r.drives.map { it.id })
    }

    @Test
    fun `trajets ecartes des trajets types`() {
        val list = listOf(
            drive(id = 1, from = "", to = "B"), // adresse inconnue
            drive(id = 2, from = "A", to = "A"), // boucle
            drive(id = 3, from = "A", to = "B", km = 0.3), // déplacement minime
            drive(id = 4, from = "A", to = "B"),
        )
        assertEquals(listOf(listOf(4)), list.routes(min = 1).map { r -> r.drives.map { it.id } })
        // la boucle Domicile → Domicile du jeu réel (5617) n'apparaît pas
        assertTrue(Fixtures.drives.routes(min = 1).none { it.from == it.to })
    }

    // ---- Pertes à l'arrêt ----

    @Test
    fun `pertes a l'arret entre trajets et charges`() {
        val from = date("2026-11-11T13:00:00+01:00")
        val to = date("2026-11-13T09:00:00+01:00")
        val idles = idlePeriods(Fixtures.drives, Fixtures.charges).filter { it.start >= from && it.start < to }
        // 5617 → 5619, 5619 → 5620, charge 722 → charge 723, 5624 → 5625 ;
        // les enchaînements de moins d'une heure (5620 → 722, 723 → 5624…) sont ignorés
        assertEquals(
            listOf(HOME to 1, PR to 4, HOME to 0, "Avenue des Sports, Montfort" to 1),
            idles.map { it.place to it.lossPct },
        )
        val day = idles[1]
        assertEquals(date("2026-11-12T08:46:54+01:00"), day.start)
        assertEquals(date("2026-11-12T17:58:42+01:00"), day.end)
        assertEquals(551 / 60.0, day.hours, 1e-9)
        assertEquals(10.453720508166969, day.pctPerDay, 1e-9)
    }

    @Test
    fun `derniere perte du jeu reel`() {
        val last = idlePeriods(Fixtures.drives, Fixtures.charges).last()
        assertEquals(date("2026-11-17T18:37:35+01:00"), last.start) // fin de 5658
        assertEquals(date("2026-11-18T08:04:51+01:00"), last.end) // début de 5661
        assertEquals(HOME, last.place)
        assertEquals(0, last.lossPct)
    }

    @Test
    fun `arret d'une heure pile retenu, 59 minutes ignore`() {
        val a = drive(id = 1, start = "2026-11-10T08:00:00+01:00", end = "2026-11-10T08:30:00+01:00", to = "Bureau", endBat = 70)
        val b = drive(id = 2, start = "2026-11-10T09:30:00+01:00", end = "2026-11-10T09:40:00+01:00", startBat = 69, endBat = 68)
        val c = drive(id = 3, start = "2026-11-10T10:39:00+01:00", startBat = 68)
        val idles = idlePeriods(listOf(a, b, c), emptyList())
        assertEquals(listOf("Bureau" to 1), idles.map { it.place to it.lossPct })
    }

    @Test
    fun `batterie inconnue ou en hausse`() {
        val a = drive(id = 1, start = "2026-11-10T08:00:00+01:00", end = "2026-11-10T08:30:00+01:00", endBat = null)
        val b = drive(id = 2, start = "2026-11-10T12:00:00+01:00", end = "2026-11-10T12:30:00+01:00", startBat = 70, endBat = 60)
        // relevé plus haut au départ suivant : pas de perte négative
        val c = drive(id = 3, start = "2026-11-10T18:00:00+01:00", startBat = 62)
        val idles = idlePeriods(listOf(c, a, b), emptyList())
        assertEquals(listOf(0), idles.map { it.lossPct })
        assertEquals(date("2026-11-10T12:30:00+01:00"), idles.single().start)
    }

    @Test
    fun `trajet sans heure de fin`() {
        val a = drive(id = 1, start = "2026-11-10T08:00:00+01:00", end = null, endBat = 70)
        val b = charge(id = 2, start = "2026-11-10T20:00:00+01:00", startBat = 65)
        val idle = idlePeriods(listOf(a), listOf(b)).single()
        assertEquals(a.start, idle.start)
        assertEquals(12.0, idle.hours, 1e-9)
        assertEquals(10.0, idle.pctPerDay, 1e-9)
    }

    @Test
    fun `perte par jour sur une duree nulle`() {
        val t = date("2026-11-10T08:00:00+01:00")
        assertEquals(0.0, Idle(t, t, HOME, 3).pctPerDay, 0.0)
    }
}
