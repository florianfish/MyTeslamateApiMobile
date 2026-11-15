package fr.florian.teslamate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import org.osmdroid.util.GeoPoint as OsmPoint

// ---------------------------------------------------------------------------
// Formats
// ---------------------------------------------------------------------------

val FR: Locale = Locale.FRENCH
private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm", FR)
private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", FR)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", FR)

fun OffsetDateTime.local() = atZoneSameInstant(ZoneId.systemDefault())
fun OffsetDateTime.fmtDateTime(): String = local().format(dateTimeFmt).replaceFirstChar { it.uppercase() }
fun OffsetDateTime.fmtDate(): String = local().format(dateFmt)
fun OffsetDateTime.fmtTime(): String = local().format(timeFmt)

fun num(v: Double?, decimals: Int = 1): String =
    if (v == null) "—" else String.format(FR, "%,.${decimals}f", v)

fun km(v: Double?) = if (v == null) "—" else if (v >= 100) "${num(v, 0)} km" else "${num(v, 1)} km"
fun kwh(v: Double?) = "${num(v, 1)} kWh"
fun euro(v: Double?) = "${num(v, 2)} €"
fun whkm(v: Double?) = if (v == null) "—" else "${num(v, 0)} Wh/km"

fun duration(min: Int): String = when {
    min < 60 -> "$min min"
    else -> "${min / 60} h ${(min % 60).toString().padStart(2, '0')}"
}

// ---------------------------------------------------------------------------
// Petits composants
// ---------------------------------------------------------------------------

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Valeur mise en avant avec son libellé (ex. « 1 234 km » / « Distance »). */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Grille de statistiques, [perRow] par ligne. */
@Composable
fun StatGrid(items: List<Pair<String, String>>, perRow: Int = 3) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (l, v) -> Stat(l, v, Modifier.weight(1f)) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    // Le libellé prend la place restante (et passe à la ligne si besoin) ; la valeur ne se coupe jamais
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

// ---------------------------------------------------------------------------
// Carte OpenStreetMap (osmdroid)
// ---------------------------------------------------------------------------

/** Type de repère : couleur et symbole de la pastille. */
enum class MarkerKind(val color: Int, val glyph: String) {
    CAR(0xFFE31937.toInt(), "🚗"),
    START(0xFF43A047.toInt(), "A"),
    END(0xFFE53935.toInt(), "B"),
    CHARGE(0xFF1E88E5.toInt(), "⚡"),
}

data class MapMarker(val point: GeoPoint, val title: String = "", val kind: MarkerKind = MarkerKind.CAR)

/** Pastille ronde (bordure blanche + symbole) dessinée à la volée, à la place du repère par défaut d'osmdroid. */
private fun markerIcon(ctx: android.content.Context, kind: MarkerKind): android.graphics.drawable.BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val size = (34 * d).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    p.color = 0x55000000 // ombre
    c.drawCircle(r, r + d, r - d, p)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(r, r, r - 2 * d, p)
    p.color = kind.color
    c.drawCircle(r, r, r - 4.5f * d, p)
    p.color = android.graphics.Color.WHITE
    p.textAlign = android.graphics.Paint.Align.CENTER
    p.textSize = 15 * d
    p.typeface = android.graphics.Typeface.DEFAULT_BOLD
    c.drawText(kind.glyph, r, r - (p.descent() + p.ascent()) / 2, p)
    return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
}

/**
 * Carte OSM : tracé [path] et/ou [markers]. Non [interactive], elle ne capte pas les gestes
 * (le défilement de l'écran passe par-dessus).
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    path: List<GeoPoint> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    interactive: Boolean = true,
    zoom: Double = 16.0,
) {
    // Bleu franc bordé de blanc : lisible sur le fond clair des tuiles OSM, quel que soit le thème
    val lineColor = 0xFF1565C0.toInt()
    Box(modifier.clip(RoundedCornerShape(12.dp))) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = true
                    minZoomLevel = 3.0
                }
            },
            update = { map ->
                map.overlays.clear()
                val pts = path.map { OsmPoint(it.lat, it.lng) }
                if (pts.size > 1) {
                    val dens = map.context.resources.displayMetrics.density
                    // Liseré blanc dessous, tracé coloré dessus
                    listOf(android.graphics.Color.WHITE to 7f, lineColor to 4f).forEach { (col, w) ->
                        map.overlays.add(Polyline(map).apply {
                            setPoints(pts)
                            outlinePaint.color = col
                            outlinePaint.strokeWidth = w * dens
                            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                            outlinePaint.isAntiAlias = true
                        })
                    }
                }
                markers.forEach { m ->
                    map.overlays.add(Marker(map).apply {
                        position = OsmPoint(m.point.lat, m.point.lng)
                        icon = markerIcon(map.context, m.kind)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = m.title
                    })
                }
                val all = pts + markers.map { OsmPoint(it.point.lat, it.point.lng) }
                map.post {
                    if (all.size == 1) {
                        map.controller.setZoom(zoom)
                        map.controller.setCenter(all.first())
                    } else if (all.size > 1) {
                        val box = BoundingBox.fromGeoPointsSafe(all)
                        if (box.latitudeSpan < 1e-4 && box.longitudeSpanWithDateLine < 1e-4) {
                            map.controller.setZoom(zoom)
                            map.controller.setCenter(all.first())
                        } else {
                            map.zoomToBoundingBox(box, false, 60)
                        }
                    }
                }
                map.invalidate()
            },
            onRelease = { it.onDetach() },
        )
        if (!interactive) {
            // Calque transparent : absorbe les gestes sans les consommer (le parent défile toujours)
            Box(Modifier.matchParentSize().pointerInput(Unit) {})
        }
    }
}

// ---------------------------------------------------------------------------
// Graphiques (Canvas, sans bibliothèque)
// ---------------------------------------------------------------------------

/** Histogramme : une barre par libellé, valeur affichée au-dessus. */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    format: (Double) -> String = { num(it, 0) },
) {
    val measurer = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val small = TextStyle(fontSize = 9.sp, color = textColor)
    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        if (values.isEmpty()) return@Canvas
        val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
        val labelH = 16.dp.toPx()
        val valueH = 14.dp.toPx()
        val chartH = size.height - labelH - valueH
        val slot = size.width / values.size
        val barW = slot * 0.62f
        values.forEachIndexed { i, v ->
            val h = (v / max * chartH).toFloat()
            val x = i * slot + (slot - barW) / 2
            drawRoundRect(
                color = color,
                topLeft = Offset(x, valueH + chartH - h),
                size = Size(barW, h.coerceAtLeast(if (v > 0) 2f else 0f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            )
            if (v > 0 && values.size <= 14) {
                val t = measurer.measure(format(v), small)
                drawText(t, topLeft = Offset(i * slot + (slot - t.size.width) / 2, valueH + chartH - h - t.size.height))
            }
            val l = labels.getOrNull(i).orEmpty()
            if (l.isNotEmpty()) {
                val t = measurer.measure(l, small)
                drawText(t, topLeft = Offset(i * slot + (slot - t.size.width) / 2, size.height - labelH + 2.dp.toPx()))
            }
        }
    }
}

/** Courbe simple (ex. puissance de charge, vitesse), avec min / max en légende. */
@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    unit: String = "",
) {
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        if (values.size < 2) return@Canvas
        val min = minOf(0.0, values.min())
        val max = values.max().let { if (it <= min) min + 1 else it }
        val left = 34.dp.toPx()
        val w = size.width - left
        val h = size.height
        listOf(min, (min + max) / 2, max).forEach { g ->
            val y = (h - (g - min) / (max - min) * h).toFloat()
            drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
            val t = measurer.measure("${g.roundToInt()}$unit", small)
            drawText(t, topLeft = Offset(0f, (y - t.size.height / 2).coerceIn(0f, h - t.size.height)))
        }
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = left + i * w / (values.size - 1)
            val y = (h - (v - min) / (max - min) * h).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

/** Jauge de batterie horizontale avec la limite de charge. */
@Composable
fun BatteryBar(level: Int?, limit: Int?, modifier: Modifier = Modifier) {
    val fill = when {
        level == null -> Color.Gray
        level < 20 -> Color(0xFFE53935)
        level < 40 -> Color(0xFFFFA000)
        else -> Color(0xFF43A047)
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val mark = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (level != null) drawRoundRect(fill, size = Size(size.width * level / 100f, size.height), cornerRadius = r)
        if (limit != null) {
            val x = size.width * limit / 100f
            drawLine(mark, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Couleur d'une consommation : vert sous la moyenne de la voiture, orange, puis rouge. */
fun consumptionColor(wh: Double?): Color = when {
    wh == null -> Color.Unspecified
    wh <= 150 -> Color(0xFF43A047)
    wh <= 190 -> Color(0xFFFFA000)
    else -> Color(0xFFE53935)
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { content() }
}
