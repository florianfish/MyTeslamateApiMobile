package fr.florian.teslamate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Consommation moyenne, réduction des séries et des tracés, fusion du delta. */
class CalculsTest {

    @Test
    fun `consommation moyenne ponderee par la distance`() {
        // 2 kWh sur 10 km et 3 kWh sur 30 km : 5 000 Wh / 40 km = 125 (et non 150, la moyenne simple)
        val list = listOf(drive(km = 10.0, kwh = 2.0), drive(km = 30.0, kwh = 3.0))
        assertEquals(125.0, list.avgConsumption()!!, 1e-9)
    }

    @Test
    fun `consommation moyenne sans energie ni distance`() {
        val list = listOf(drive(km = 10.0, kwh = 2.0), drive(km = 100.0, kwh = null), drive(km = 0.0, kwh = 1.0))
        assertEquals(200.0, list.avgConsumption()!!, 1e-9)
        assertNull(emptyList<Drive>().avgConsumption())
        assertNull(listOf(drive(kwh = null)).avgConsumption())
    }

    @Test
    fun `consommation moyenne du jeu reel`() {
        assertEquals(138.7956618459893, Fixtures.drives.avgConsumption()!!, 1e-9)
    }

    @Test
    fun `serie courte inchangee`() {
        val s = listOf(1.0, 2.0, 3.0)
        assertSame(s, s.downsample(3))
    }

    @Test
    fun `serie reduite par moyennes de paquets`() {
        val s = (1..1000).map { it.toDouble() }.downsample(100)
        assertEquals(100, s.size)
        assertEquals(5.5, s.first(), 1e-9) // moyenne de 1..10
        assertEquals(995.5, s.last(), 1e-9) // moyenne de 991..1000
        assertEquals(500.5, s.average(), 1e-9)
    }

    @Test
    fun `serie reduite avec paquets inegaux`() {
        // 10 valeurs en 3 paquets : [0..2], [3..5], [6..9]
        assertEquals(listOf(1.0, 4.0, 7.5), (0..9).map { it.toDouble() }.downsample(3))
    }

    @Test
    fun `trace simplifie garde le premier et le dernier point`() {
        val path = (0 until 100).map { GeoPoint(it.toDouble(), -it.toDouble()) }
        val s = path.simplify(10)
        assertEquals(10, s.size)
        assertEquals(path.first(), s.first())
        assertEquals(path.last(), s.last())
        assertEquals((0..99 step 11).map { path[it] }, s)
        assertSame(path, path.simplify(100))
    }

    @Test
    fun `fusion du delta par id`() {
        val old = listOf(drive(id = 2, start = "2026-11-12T08:00:00+01:00"), drive(id = 1, start = "2026-11-11T08:00:00+01:00"))
        // le trajet 2 revient du delta avec une distance corrigée, le 3 est nouveau
        val new = listOf(drive(id = 3, start = "2026-11-13T08:00:00+01:00"), drive(id = 2, start = "2026-11-12T08:00:00+01:00", km = 12.0))
        val res = mergeById(old, new, { it.id }, { it.start })
        assertEquals(listOf(3, 2, 1), res.map { it.id })
        assertEquals(12.0, res[1].distance, 0.0)
    }

    @Test
    fun `fusion complete depuis un cache vide`() {
        val res = mergeById(emptyList(), Fixtures.drives.shuffled(java.util.Random(1)), { it.id }, { it.start })
        assertEquals(Fixtures.drives, res)
    }
}
