package fr.florian.teslamate

import org.json.JSONObject
import java.time.OffsetDateTime

/** Jeux de données réels anonymisés (app/src/test/resources), lus comme des réponses de l'API. */
object Fixtures {
    private fun read(name: String): String =
        requireNotNull(Fixtures::class.java.getResource("/$name")) { "Jeu de données absent : $name" }.readText()

    private fun list(name: String, key: String) =
        JSONObject(read(name)).getJSONObject("data").getJSONArray(key).objects()

    /** 15 trajets du 10 au 18 novembre 2026, du plus récent au plus ancien (ordre de l'API). */
    val drives: List<Drive> by lazy { list("drives.json", "drives").map { TeslaMateApi.parseDrive(it) } }

    /** 7 charges du 10 octobre au 13 novembre 2026, de la plus récente à la plus ancienne. */
    val charges: List<Charge> by lazy { list("charges.json", "charges").map { TeslaMateApi.parseCharge(it) } }

    fun drive(id: Int) = drives.single { it.id == id }
    fun charge(id: Int) = charges.single { it.id == id }
}

const val HOME = "Domicile"
const val PR = "Parc relais Nord, Villeneuve"

fun date(s: String): OffsetDateTime = OffsetDateTime.parse(s)

/** Trajet fabriqué pour les cas limites. */
fun drive(
    id: Int = 1,
    start: String = "2026-11-10T08:00:00+01:00",
    end: String? = null,
    from: String = "A",
    to: String = "B",
    km: Double = 10.0,
    min: Int = 15,
    startBat: Int? = 80,
    endBat: Int? = 78,
    kwh: Double? = 1.5,
    conso: Double? = 150.0,
    temp: Double? = 10.0,
) = Drive(id, date(start), end?.let { date(it) }, from, to, km, min, null, null, startBat, endBat, temp, kwh, conso)

/** Charge fabriquée pour les cas limites. */
fun charge(
    id: Int = 1,
    start: String = "2026-11-10T20:00:00+01:00",
    end: String? = null,
    address: String = HOME,
    added: Double = 10.0,
    cost: Double? = null,
    min: Int = 60,
    startBat: Int? = 50,
    endBat: Int? = 70,
) = Charge(id, date(start), end?.let { date(it) }, address, added, null, cost, min, startBat, endBat, null, null, null)
