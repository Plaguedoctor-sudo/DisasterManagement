package network.disaster.management.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import network.disaster.management.map.*
import network.disaster.management.simulation.SurvivorSimulationEngine
import kotlin.math.*

/**
 * TacticalLocalMapView: High-Contrast Offline Tactical Disaster GIS Map Canvas.
 *
 * Renders real disaster incidents and telemetry received over the mesh:
 * - Concentric distance range rings (100m, 250m, 500m, 750m, 1000m).
 * - Real user GPS location at center with pulsing position indicator.
 * - Real distress beacons (🔴 SOS / TRAPPED / AID).
 * - Real environmental hazards (⚠️ Road Blocked, Live Wire, Fire, Flood, Collapse, Gas).
 * - Real resource requests & supply caches (🟡 Water, Medical, Food, Shelter, Power).
 * - Real civilian check-ins (🟢 SAFE).
 * - Interactive touch-to-inspect marker selection.
 * - Offline vector HUD operation without reliance on online tile services or satellite internet.
 */
class TacticalLocalMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // User's current geographical location (defaults to initial point, updated via GPS)
    var userLocation: TacticalPoint = TacticalPoint(20.39712, 76.33967)
        set(value) {
            field = value
            invalidate()
        }

    // Real incidents parsed from mesh messages
    var incidents: List<MapIncident> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    // Active category filter (null = show all)
    var activeFilter: DisasterIncidentType? = null
        set(value) {
            field = value
            invalidate()
        }

    // Currently selected / inspected incident
    var selectedIncident: MapIncident? = null
        set(value) {
            field = value
            invalidate()
        }

    // Callback when user taps an incident on the map
    var onIncidentSelected: ((MapIncident) -> Unit)? = null

    // For backwards compatibility with unit tests
    var simulationEngine: SurvivorSimulationEngine? = null
        set(value) {
            field = value
            invalidate()
        }

    // Map Display Scale: meters per pixel
    private var scaleMetersPerPixel = 1.6f

    // Screen offsets for rendered incidents to enable touch selection: incident -> (x, y)
    private val renderedMarkerCoords = mutableListOf<Triple<MapIncident, Float, Float>>()

    // Graphic Paints
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#070B10")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#14202E")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val rangeRingPaint = Paint().apply {
        color = Color.parseColor("#1E334A")
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val ringLabelPaint = Paint().apply {
        color = Color.parseColor("#4A7A96")
        textSize = 24f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    // User Location Paints
    private val userCenterPaint = Paint().apply {
        color = Color.parseColor("#38BDF8") // Sky-400
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val userPulsePaint = Paint().apply {
        color = Color.parseColor("#3338BDF8")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val userStrokePaint = Paint().apply {
        color = Color.parseColor("#0284C7")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // Incident Paints
    private val sosFillPaint = Paint().apply {
        color = Color.parseColor("#EF4444") // Red
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val sosPulsePaint = Paint().apply {
        color = Color.parseColor("#40EF4444")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hazardFillPaint = Paint().apply {
        color = Color.parseColor("#F97316") // Orange
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val supplyFillPaint = Paint().apply {
        color = Color.parseColor("#EAB308") // Yellow
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val safeFillPaint = Paint().apply {
        color = Color.parseColor("#22C55E") // Green
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val selectedHaloPaint = Paint().apply {
        color = Color.parseColor("#FFFFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val selectedFillHaloPaint = Paint().apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val subLabelPaint = Paint().apply {
        color = Color.parseColor("#94A3B8")
        textSize = 20f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    fun zoomIn() {
        scaleMetersPerPixel = (scaleMetersPerPixel * 0.75f).coerceAtLeast(0.4f)
        invalidate()
    }

    fun zoomOut() {
        scaleMetersPerPixel = (scaleMetersPerPixel * 1.33f).coerceAtMost(5.0f)
        invalidate()
    }

    fun centerOn(point: TacticalPoint) {
        userLocation = point
        invalidate()
    }

    fun clearSelection() {
        selectedIncident = null
        invalidate()
    }

    fun setFilter(filter: DisasterIncidentType?) {
        activeFilter = filter
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val touchX = event.x
            val touchY = event.y
            val hitRadiusPx = 50f // 50px hit box

            // Check if user tapped near any rendered incident
            val hit = renderedMarkerCoords.firstOrNull { (_, mx, my) ->
                val dx = touchX - mx
                val dy = touchY - my
                (dx * dx + dy * dy) <= (hitRadiusPx * hitRadiusPx)
            }

            if (hit != null) {
                selectedIncident = hit.first
                invalidate()
                onIncidentSelected?.invoke(hit.first)
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val centerY = h / 2f

        renderedMarkerCoords.clear()

        // 1. Tactical Grid & Background
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // Draw crosshair axes
        canvas.drawLine(0f, centerY, w, centerY, gridPaint)
        canvas.drawLine(centerX, 0f, centerX, h, gridPaint)

        // 2. Concentric Range Rings (100m, 250m, 500m, 750m, 1000m)
        val rings = listOf(100f, 250f, 500f, 750f, 1000f)
        for (distMeters in rings) {
            val rPx = distMeters / scaleMetersPerPixel
            if (rPx < max(w, h)) {
                canvas.drawCircle(centerX, centerY, rPx, rangeRingPaint)
                canvas.drawText("${distMeters.roundToInt()}m", centerX + 8f, centerY - rPx + 24f, ringLabelPaint)
            }
        }

        // Draw Compass Cardinals
        canvas.drawText("N", centerX - 10f, 36f, ringLabelPaint)
        canvas.drawText("S", centerX - 10f, h - 14f, ringLabelPaint)
        canvas.drawText("W", 16f, centerY + 8f, ringLabelPaint)
        canvas.drawText("E", w - 36f, centerY + 8f, ringLabelPaint)

        // Active Reference Center Point: prefer userLocation, or simulationEngine survivor if set
        val refLocation = simulationEngine?.survivor?.location ?: userLocation

        // Utility to convert TacticalPoint to canvas pixel offsets relative to reference center
        fun toPixelOffset(pt: TacticalPoint): Pair<Float, Float> {
            val latDiff = pt.lat - refLocation.lat
            val lonDiff = pt.lon - refLocation.lon
            val metersY = -(latDiff * 111320.0).toFloat()
            val metersX = (lonDiff * 111320.0 * cos(Math.toRadians(refLocation.lat))).toFloat()
            val pxX = centerX + (metersX / scaleMetersPerPixel)
            val pxY = centerY + (metersY / scaleMetersPerPixel)
            return Pair(pxX, pxY)
        }

        // 3. Render Real Mesh Disaster Incidents
        val filter = activeFilter
        val displayIncidents = if (filter == null) incidents else incidents.filter { it.type == filter }

        for (inc in displayIncidents) {
            val (ix, iy) = toPixelOffset(inc.location)

            // Skip drawing if outside visible bounds
            if (ix < -100f || ix > w + 100f || iy < -100f || iy > h + 100f) continue

            renderedMarkerCoords.add(Triple(inc, ix, iy))

            val isSelected = selectedIncident?.id == inc.id
            if (isSelected) {
                canvas.drawCircle(ix, iy, 34f, selectedFillHaloPaint)
                canvas.drawCircle(ix, iy, 28f, selectedHaloPaint)
            }

            val dist = refLocation.distanceTo(inc.location).roundToInt()

            when (inc.type) {
                DisasterIncidentType.DISTRESS_SOS -> {
                    // Pulsing Red Beacon
                    canvas.drawCircle(ix, iy, 28f, sosPulsePaint)
                    canvas.drawCircle(ix, iy, 14f, sosFillPaint)
                    canvas.drawText("🔴 SOS: ${inc.senderName}", ix + 20f, iy - 6f, labelPaint)
                    canvas.drawText("${dist}m away • ${inc.title.take(30)}", ix + 20f, iy + 18f, subLabelPaint)
                }
                DisasterIncidentType.HAZARD_ALERT -> {
                    // Orange Warning Marker (Triangle)
                    val path = Path().apply {
                        moveTo(ix, iy - 16f)
                        lineTo(ix + 16f, iy + 12f)
                        lineTo(ix - 16f, iy + 12f)
                        close()
                    }
                    canvas.drawPath(path, hazardFillPaint)
                    canvas.drawText("⚠️ HAZARD: ${inc.title.take(28)}", ix + 22f, iy - 6f, labelPaint)
                    canvas.drawText("${dist}m away • @${inc.senderName}", ix + 22f, iy + 18f, subLabelPaint)
                }
                DisasterIncidentType.SUPPLY_REQUEST -> {
                    // Yellow Resource Request Marker
                    canvas.drawCircle(ix, iy, 13f, supplyFillPaint)
                    canvas.drawText("🟡 NEED: ${inc.title.take(28)}", ix + 20f, iy - 6f, labelPaint)
                    canvas.drawText("${dist}m away • @${inc.senderName}", ix + 20f, iy + 18f, subLabelPaint)
                }
                DisasterIncidentType.CIVILIAN_SAFE -> {
                    // Green Safe Check-in Marker
                    canvas.drawCircle(ix, iy, 12f, safeFillPaint)
                    canvas.drawText("🟢 SAFE: @${inc.senderName}", ix + 18f, iy - 6f, labelPaint)
                    canvas.drawText("${dist}m away • Check-in verified", ix + 18f, iy + 18f, subLabelPaint)
                }
            }
        }

        // 4. Render Center User Marker (Pulsing Cyan/Blue core)
        canvas.drawCircle(centerX, centerY, 24f, userPulsePaint)
        canvas.drawCircle(centerX, centerY, 14f, userStrokePaint)
        canvas.drawCircle(centerX, centerY, 8f, userCenterPaint)
        canvas.drawText("YOU (CURRENT POSITION)", centerX - 90f, centerY - 28f, labelPaint)
        val coordSummary = String.format(java.util.Locale.US, "%.5f, %.5f", refLocation.lat, refLocation.lon)
        canvas.drawText(coordSummary, centerX - 65f, centerY + 36f, ringLabelPaint)

        // 5. Fallback: If no real incidents and simulation engine is provided (for unit test compatibility)
        val engine = simulationEngine
        if (displayIncidents.isEmpty() && engine != null) {
            renderSimulationObjects(canvas, engine, centerX, centerY, ::toPixelOffset)
        }
    }

    private fun renderSimulationObjects(
        canvas: Canvas,
        engine: SurvivorSimulationEngine,
        centerX: Float,
        centerY: Float,
        toPixelOffset: (TacticalPoint) -> Pair<Float, Float>
    ) {
        val survivor = engine.survivor
        for (hz in engine.hazards) {
            val (hx, hy) = toPixelOffset(hz.location)
            val radPx = (hz.radiusMeters / scaleMetersPerPixel).toFloat()
            canvas.drawCircle(hx, hy, radPx, hazardFillPaint)
            canvas.drawText("⚠️ ${hz.name}", hx - 50f, hy - radPx - 8f, labelPaint)
        }
        for (sc in engine.supplyCaches) {
            val (sx, sy) = toPixelOffset(sc.location)
            if (sc.isCollected) continue
            canvas.drawCircle(sx, sy, 14f, supplyFillPaint)
            canvas.drawText(sc.name, sx + 20f, sy + 6f, labelPaint)
        }
    }

    fun toggleHighlightSupply(id: String): Boolean {
        val sc = simulationEngine?.supplyCaches?.find { it.id == id } ?: return false
        sc.isHighlighted = !sc.isHighlighted
        invalidate()
        return sc.isHighlighted
    }
}
