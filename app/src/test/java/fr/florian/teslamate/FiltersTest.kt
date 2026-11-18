package fr.florian.teslamate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Filtres et tris des écrans Trajets et Charges. Fuseau Europe/Paris fixé dans app/build.gradle.kts. */
class FiltersTest {
    private val drives = Fixtures.drives
    private val charges = Fixtures.charges

    private fun List<Drive>.ids() = map { it.id }
    private fun List<Charge>.cids() = map { it.id }
    private fun DriveFilter.run() = drives.filtered(this)
    private fun ChargeFilter.run() = charges.filtered(this)

    // ---- Périodes ----

    @Test
    fun `periodes predefinies`() {
        val p = Period.presets(LocalDate.of(2026, 11, 18)).associateBy { it.label }
        assertEquals(LocalDate.of(2026, 11, 12), p.getValue("7 jours").from)
        assertEquals(LocalDate.of(2026, 10, 20), p.getValue("30 jours").from)
        assertEquals(LocalDate.of(2026, 11, 1), p.getValue("Ce mois").from)
        assertEquals(LocalDate.of(2026, 1, 1), p.getValue("Cette année").from)
        assertEquals(LocalDate.of(2025, 1, 1), p.getValue("2025").from)
        assertEquals(LocalDate.of(2026, 1, 1), p.getValue("2025").to)
        assertEquals(Period.ALL, p.getValue("Tout"))

        assertEquals(listOf(5661, 5658, 5657, 5655, 5625, 5624, 5620, 5619), DriveFilter(period = p.getValue("7 jours")).run().ids())
        assertEquals(14, DriveFilter(period = p.getValue("Ce mois")).run().size)
        assertTrue(DriveFilter(period = p.getValue("2025")).run().isEmpty())
    }

    @Test
    fun `borne de debut incluse, borne de fin exclue`() {
        val p = Period("test", LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 13))
        assertEquals(listOf(5620, 5619, 5616, 5615, 5614, 5612), DriveFilter(period = p).run().ids())
    }

    @Test
    fun `le jour est celui de l'heure locale`() {
        // 23 h 30 UTC le 10 = 0 h 30 le 11 à Paris
        val d = drive(start = "2026-11-10T23:30:00Z")
        assertTrue(Period("", LocalDate.of(2026, 11, 11), null).contains(d.start))
        assertTrue(!Period("", null, LocalDate.of(2026, 11, 11)).contains(d.start))
    }

    // ---- Trajets ----

    @Test
    fun `petits deplacements masques par defaut`() {
        val res = DriveFilter(period = Period.ALL).run()
        assertEquals(14, res.size)
        assertTrue(5617 !in res.ids())
        assertEquals(15, DriveFilter(period = Period.ALL, hideTiny = false).run().size)
    }

    @Test
    fun `tranches de distance`() {
        assertEquals(setOf(5615, 5625, 5624), DriveFilter(period = Period.ALL, distance = DistanceRange.MEDIUM).run().ids().toSet())
        assertEquals(11, DriveFilter(period = Period.ALL, distance = DistanceRange.SHORT).run().size)
        assertTrue(DriveFilter(period = Period.ALL, distance = DistanceRange.LONG).run().isEmpty())
        // 10 km pile : tranche 10–50, pas « moins de 10 »
        val ten = listOf(drive(km = 10.0))
        assertEquals(1, ten.filtered(DriveFilter(period = Period.ALL, distance = DistanceRange.MEDIUM)).size)
        assertEquals(0, ten.filtered(DriveFilter(period = Period.ALL, distance = DistanceRange.SHORT)).size)
    }

    @Test
    fun `recherche sur le depart ou l'arrivee`() {
        // insensible à la casse, couvre « Parc relais Nord » et « Parc relais du Nord »
        assertEquals(listOf(5661, 5657, 5655, 5620, 5619, 5610, 5609), DriveFilter(period = Period.ALL, query = "parc relais").run().ids())
        // espaces ignorés, départ (5625) comme arrivée (5624)
        assertEquals(listOf(5625, 5624), DriveFilter(period = Period.ALL, query = "  montfort ").run().ids())
        assertTrue(DriveFilter(period = Period.ALL, query = "introuvable").run().isEmpty())
    }

    @Test
    fun `tris des trajets`() {
        assertEquals(5661, DriveFilter(period = Period.ALL, sort = DriveSort.RECENT).run().first().id)
        assertEquals(5609, DriveFilter(period = Period.ALL, sort = DriveSort.OLDEST).run().first().id)
        assertEquals(listOf(5624, 5625, 5615), DriveFilter(period = Period.ALL, sort = DriveSort.LONGEST).run().ids().take(3))
        val eco = DriveFilter(period = Period.ALL, sort = DriveSort.ECO).run()
        assertEquals(14, eco.size)
        assertEquals(5616, eco.first().id)
        assertEquals(5658, DriveFilter(period = Period.ALL, sort = DriveSort.GREEDY).run().first().id)
    }

    @Test
    fun `tri par consommation sans les trajets non mesures`() {
        val list = listOf(drive(id = 1, conso = 150.0), drive(id = 2, conso = null), drive(id = 3, conso = 120.0))
        assertEquals(listOf(3, 1), list.filtered(DriveFilter(period = Period.ALL, sort = DriveSort.ECO)).ids())
        assertEquals(listOf(1, 3), list.filtered(DriveFilter(period = Period.ALL, sort = DriveSort.GREEDY)).ids())
        assertEquals(3, list.filtered(DriveFilter(period = Period.ALL)).size)
    }

    // ---- Charges ----

    @Test
    fun `charges vides masquees par defaut`() {
        assertEquals(listOf(723, 721, 720, 719, 712), ChargeFilter(period = Period.ALL).run().cids())
        assertEquals(7, ChargeFilter(period = Period.ALL, hideEmpty = false).run().size)
    }

    @Test
    fun `charges payantes, type et lieu`() {
        assertEquals(listOf(723, 720, 719), ChargeFilter(period = Period.ALL, paidOnly = true).run().cids())
        assertEquals(5, ChargeFilter(period = Period.ALL, type = ChargeType.AC).run().size)
        assertTrue(ChargeFilter(period = Period.ALL, type = ChargeType.DC).run().isEmpty())
        assertEquals(listOf(723, 720, 719), ChargeFilter(period = Period.ALL, query = "domicile").run().cids())
        val p30 = Period.presets(LocalDate.of(2026, 11, 18)).single { it.label == "30 jours" }
        assertEquals(listOf(723, 721, 720, 719), ChargeFilter(period = p30).run().cids())
    }

    @Test
    fun `charge rapide filtree en DC`() {
        val list = listOf(charge(id = 1), charge(id = 2, address = "Tesla Supercharger, Villeneuve"))
        assertEquals(listOf(2), list.filtered(ChargeFilter(period = Period.ALL, type = ChargeType.DC)).cids())
        assertEquals(listOf(1), list.filtered(ChargeFilter(period = Period.ALL, type = ChargeType.AC)).cids())
    }

    @Test
    fun `tris des charges`() {
        assertEquals(712, ChargeFilter(period = Period.ALL, sort = ChargeSort.ENERGY).run().first().id)
        assertEquals(720, ChargeFilter(period = Period.ALL, sort = ChargeSort.COST).run().first().id)
        assertEquals(712, ChargeFilter(period = Period.ALL, sort = ChargeSort.OLDEST).run().first().id)
        // prix au kWh : 0,1717 > 0,1714 > 0,1696 ; les charges gratuites sont écartées
        assertEquals(listOf(720, 719, 723), ChargeFilter(period = Period.ALL, sort = ChargeSort.PRICE).run().cids())
    }

    @Test
    fun `lieux de charge frequents`() {
        assertEquals(listOf(HOME, PR, "Famille"), charges.topPlaces())
        assertEquals(listOf(HOME), charges.topPlaces(1))
    }
}
