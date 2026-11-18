package fr.florian.teslamate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/** Lecture des réponses de l'API et aller-retour par le cache disque (toJson → parse). */
class ParsingTest {

    @Test
    fun `lecture d'un trajet complet`() {
        assertEquals(15, Fixtures.drives.size)
        val d = Fixtures.drives.first()
        assertEquals(5661, d.id)
        assertEquals(date("2026-11-18T08:04:51+01:00"), d.start)
        assertEquals(date("2026-11-18T08:17:48+01:00"), d.end)
        assertEquals(HOME, d.startAddress)
        assertEquals("Parc relais du Nord, Villeneuve", d.endAddress)
        assertEquals(6.772458999999799, d.distance, 1e-12)
        assertEquals(13, d.durationMin)
        assertEquals(90.0, d.speedMax!!, 0.0)
        assertEquals(31.25750307692215, d.speedAvg!!, 1e-12)
        assertEquals(50, d.startBattery)
        assertEquals(48, d.endBattery)
        assertEquals(12.3, d.outsideTemp!!, 0.0)
        assertEquals(0.9392688, d.energyKwh!!, 1e-12)
        assertEquals(138.68947748521296, d.consumption!!, 1e-12)
    }

    @Test
    fun `trajet sans energie ni consommation`() {
        val d = Fixtures.drive(5617)
        assertEquals(0.040498000002116896, d.distance, 1e-12)
        assertNull(d.energyKwh)
        assertNull(d.consumption)
    }

    @Test
    fun `champs absents, nuls ou invalides`() {
        val d = TeslaMateApi.parseDrive(
            JSONObject("""{"drive_id": 7, "start_date": "pas une date", "start_address": null, "speed_max": null, "battery_details": {}}"""),
        )
        assertEquals(7, d.id)
        assertEquals(OffsetDateTime.MIN, d.start)
        assertNull(d.end)
        assertEquals("", d.startAddress)
        assertEquals("", d.endAddress)
        assertEquals(0.0, d.distance, 0.0)
        assertEquals(0, d.durationMin)
        assertNull(d.speedMax)
        assertNull(d.startBattery)
        assertNull(d.outsideTemp)
        assertNull(d.energyKwh)

        val c = TeslaMateApi.parseCharge(JSONObject("""{"charge_id": 8, "cost": null}"""))
        assertEquals(8, c.id)
        assertEquals("", c.address)
        assertEquals(0.0, c.energyAdded, 0.0)
        assertNull(c.cost)
        assertNull(c.latitude)
    }

    @Test
    fun `lecture d'une charge`() {
        assertEquals(7, Fixtures.charges.size)
        val c = Fixtures.charges.first()
        assertEquals(723, c.id)
        assertEquals(date("2026-11-13T03:00:24+01:00"), c.start)
        assertEquals(date("2026-11-13T07:29:24+01:00"), c.end)
        assertEquals(HOME, c.address)
        assertEquals(13.74, c.energyAdded, 0.0)
        assertEquals(14.68, c.energyUsed!!, 0.0)
        assertEquals(2.33, c.cost!!, 0.0)
        assertEquals(269, c.durationMin)
        assertEquals(64, c.startBattery)
        assertEquals(88, c.endBattery)
        assertEquals(10.6, c.outsideTemp!!, 0.0)
        assertEquals(47.000012, c.latitude!!, 0.0)
        assertEquals(2.000011, c.longitude!!, 0.0)
    }

    @Test
    fun `charge sans energie`() {
        val c = Fixtures.charge(722)
        assertEquals(0.0, c.energyAdded, 0.0)
        assertEquals(0, c.durationMin)
        assertNull(c.avgPowerKw)
        assertFalse(c.isDc)
    }

    @Test
    fun `estimation AC ou DC`() {
        // 13,74 kWh en 4 h 29 : ~3 kW, charge lente
        assertEquals(3.0647, Fixtures.charge(723).avgPowerKw!!, 1e-4)
        assertTrue(Fixtures.charges.none { it.isDc })
        // Superchargeur repéré par l'adresse, même lent
        assertTrue(charge(address = "Tesla Supercharger, Villeneuve", added = 5.0, min = 60).isDc)
        // 50 kWh en 1 h : 50 kW, charge rapide
        assertTrue(charge(added = 50.0, min = 60).isDc)
        assertFalse(charge(added = 25.0, min = 60).isDc)
    }

    private fun point(power: Double?, phases: Int?, fast: Boolean? = null) =
        ChargePoint(null, null, null, power, null, null, null, phases, null, false, fast)

    @Test
    fun `type de charge d'apres les points`() {
        assertNull(emptyList<ChargePoint>().isDc())
        assertNull(listOf(point(0.0, 3), point(null, 1)).isDc()) // aucun point actif
        assertFalse(listOf(point(7.0, 1), point(7.2, 1), point(0.0, 0)).isDc()!!) // AC monophasé, point à l'arrêt ignoré
        assertTrue(listOf(point(120.0, null), point(150.0, 0), point(80.0, 1)).isDc()!!) // majorité sans phase
        assertFalse(listOf(point(11.0, 0), point(11.0, 3)).isDc()!!) // égalité : pas une majorité
        assertTrue(listOf(point(7.0, 1, fast = true)).isDc()!!) // chargeur rapide signalé
    }

    @Test
    fun `aller-retour cache des trajets`() {
        Fixtures.drives.forEach { assertEquals(it, TeslaMateApi.parseDrive(JSONObject(it.toJson().toString()))) }
    }

    @Test
    fun `aller-retour cache des charges`() {
        Fixtures.charges.forEach { assertEquals(it, TeslaMateApi.parseCharge(JSONObject(it.toJson().toString()))) }
    }

    @Test
    fun `aller-retour cache avec valeurs nulles`() {
        val d = drive(end = null, startBat = null, endBat = null, kwh = null, conso = null, temp = null)
        assertEquals(d, TeslaMateApi.parseDrive(JSONObject(d.toJson().toString())))
        val c = charge(end = null, cost = null, startBat = null)
        assertEquals(c, TeslaMateApi.parseCharge(JSONObject(c.toJson().toString())))
    }
}
