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
 * TacticalLocalMapView: High-Contrast Offline Tactical Map Canvas.
 *
 * Renders an off-grid tactical 2D map for trapped survivors:
 * - Concentric distance range rings (100m, 250m, 500m, 1000m).
 * - Trapped Survivor position with dynamic RF footprint circle (Stealth 0m vs Active 65m).
 * - Hostile SIGINT Drones & Ground Patrols with detection threat cones (RED).
 * - Environmental hazards: Active fire, chemical plumes, structural collapses (ORANGE/YELLOW).
 * - Crucial survival points: Potable water, trauma kits, extraction safehouses (GREEN/CYAN).
 * - Full offline operation without reliance on online tile services or satellite backhauls.
 */
class TacticalLocalMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var simulationEngine: SurvivorSimulationEngine? = null
        set(value) {
            field = value
            invalidate()
        }

    // Map Display Scale: meters per pixel
    private var scaleMetersPerPixel = 1.6f // 1 pixel = ~1.6 meters (shows ~500m radius on a 1080p display)

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
        textSize = 26f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    private val survivorPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val survivorPulsePaint = Paint().apply {
        color = Color.parseColor("#3300FF66")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val rfFootprintPaint = Paint().apply {
        color = Color.parseColor("#FFCC00")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        isAntiAlias = true
    }

    private val hostilePaint = Paint().apply {
        color = Color.parseColor("#FF3333")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hostileDetectionPaint = Paint().apply {
        color = Color.parseColor("#26FF3333")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hostileBorderPaint = Paint().apply {
        color = Color.parseColor("#80FF3333")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val hazardPaint = Paint().apply {
        color = Color.parseColor("#33FF8800")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val hazardBorderPaint = Paint().apply {
        color = Color.parseColor("#FFAA00")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val supplyPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val highlightPaint = Paint().apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        isAntiAlias = true
    }

    private val highlightHaloPaint = Paint().apply {
        color = Color.parseColor("#33FFD700")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val trapPaint = Paint().apply {
        color = Color.parseColor("#FF1100")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val trapZonePaint = Paint().apply {
        color = Color.parseColor("#33FF1100")
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
        color = Color.parseColor("#99AABB")
        textSize = 20f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    private val threatLinePaint = Paint().apply {
        color = Color.parseColor("#FF2222")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        isAntiAlias = true
    }

    fun zoomIn() {
        scaleMetersPerPixel = (scaleMetersPerPixel * 0.75f).coerceAtLeast(0.5f)
        invalidate()
    }

    fun zoomOut() {
        scaleMetersPerPixel = (scaleMetersPerPixel * 1.33f).coerceAtMost(4.5f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val centerY = h / 2f

        // 1. Tactical Grid & Background
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // Draw crosshair axes
        canvas.drawLine(0f, centerY, w, centerY, gridPaint)
        canvas.drawLine(centerX, 0f, centerX, h, gridPaint)

        // 2. Concentric Range Rings (100m, 250m, 500m, 750m)
        val rings = listOf(100f, 250f, 500f, 750f)
        for (distMeters in rings) {
            val rPx = distMeters / scaleMetersPerPixel
            canvas.drawCircle(centerX, centerY, rPx, rangeRingPaint)
            canvas.drawText("${distMeters.roundToInt()}m", centerX + 8f, centerY - rPx + 24f, ringLabelPaint)
        }

        // Draw Compass Cardinals
        canvas.drawText("N", centerX - 10f, 36f, ringLabelPaint)
        canvas.drawText("S", centerX - 10f, h - 14f, ringLabelPaint)
        canvas.drawText("W", 16f, centerY + 8f, ringLabelPaint)
        canvas.drawText("E", w - 36f, centerY + 8f, ringLabelPaint)

        val engine = simulationEngine ?: return
        val survivor = engine.survivor

        // Utility to convert TacticalPoint to canvas pixel offsets relative to survivor center
        fun toPixelOffset(pt: TacticalPoint): Pair<Float, Float> {
            val latDiff = pt.lat - survivor.location.lat
            val lonDiff = pt.lon - survivor.location.lon
            // ~111,320 meters per latitude degree; longitude adjusted for cosine
            val metersY = -(latDiff * 111320.0).toFloat()
            val metersX = (lonDiff * 111320.0 * cos(Math.toRadians(survivor.location.lat))).toFloat()
            val pxX = centerX + (metersX / scaleMetersPerPixel)
            val pxY = centerY + (metersY / scaleMetersPerPixel)
            return Pair(pxX, pxY)
        }

        // 3. Render Environmental Hazards
        for (hz in engine.hazards) {
            val (hx, hy) = toPixelOffset(hz.location)
            val radPx = (hz.radiusMeters / scaleMetersPerPixel).toFloat()
            canvas.drawCircle(hx, hy, radPx, hazardPaint)
            canvas.drawCircle(hx, hy, radPx, hazardBorderPaint)
            canvas.drawText("⚠️ ${hz.name}", hx - 50f, hy - radPx - 8f, labelPaint)
        }

        // 4. Render Supply Caches & Safehouse
        for (sc in engine.supplyCaches) {
            val (sx, sy) = toPixelOffset(sc.location)
            if (sc.isCollected) {
                // Dim collected supplies
                continue
            }

            // Highlight ring & halo if resource is prioritized by user
            if (sc.isHighlighted) {
                canvas.drawCircle(sx, sy, 32f, highlightHaloPaint)
                canvas.drawCircle(sx, sy, 22f, highlightPaint)
            }

            val symbol = when (sc.type) {
                SupplyType.POTABLE_WATER -> "💧 WATER"
                SupplyType.TRAUMA_KIT -> "🩹 MED-KIT"
                SupplyType.FOOD_RATIONS -> "🥫 FOOD"
                SupplyType.SHELTER_EXTRACTION,
                SupplyType.EXTRACTION_BUNKER -> "🚩 EXTRACTION SAFE-HOUSE"
            }
            canvas.drawCircle(sx, sy, 14f, supplyPaint)
            
            val prefix = if (sc.isHighlighted) "⭐ [PRIORITY] " else ""
            canvas.drawText("$prefix$symbol", sx + 20f, sy + 6f, labelPaint)
            val dist = survivor.location.distanceTo(sc.location).roundToInt()
            val note = if (sc.customNotes.isNotBlank()) " • ${sc.customNotes}" else ""
            canvas.drawText("${dist}m$note", sx + 20f, sy + 28f, subLabelPaint)
        }

        // 4b. Render Detected Ambush Traps (Counter-Intel Hostile Actor Detection)
        val traps = network.disaster.management.security.HostileActorDetector.shared.getDetectedAmbushTraps()
        for (trap in traps) {
            val (tx, ty) = toPixelOffset(trap.trapLocation)
            canvas.drawCircle(tx, ty, 50f, trapZonePaint)
            canvas.drawCircle(tx, ty, 18f, trapPaint)
            canvas.drawText("💀 AMBUSH TRAP! (${trap.hostileSender})", tx + 24f, ty - 4f, labelPaint)
            canvas.drawText("DANGER: In hostile killzone (${trap.distToHostilePatrolMeters.roundToInt()}m)", tx + 24f, ty + 18f, subLabelPaint)
        }

        // 5. Render Hostile Threats & Direction-Finding Cones
        for (ht in engine.hostiles) {
            val (hx, hy) = toPixelOffset(ht.location)
            val detPx = (ht.detectionRadiusMeters / scaleMetersPerPixel).toFloat()

            // Hostile sensor detection zone
            canvas.drawCircle(hx, hy, detPx, hostileDetectionPaint)
            canvas.drawCircle(hx, hy, detPx, hostileBorderPaint)

            // Hostile marker (Diamond)
            val path = Path().apply {
                moveTo(hx, hy - 18f)
                lineTo(hx + 18f, hy)
                lineTo(hx, hy + 18f)
                lineTo(hx - 18f, hy)
                close()
            }
            canvas.drawPath(path, hostilePaint)

            val dist = survivor.location.distanceTo(ht.location).roundToInt()
            canvas.drawText("⛔ ${ht.name}", hx + 24f, hy - 6f, labelPaint)
            canvas.drawText("RANGE: ${dist}m • DF SENSOR: ${ht.detectionRadiusMeters.roundToInt()}m", hx + 24f, hy + 18f, subLabelPaint)

            // If continuous RF broadcast intersects hostile sensors, draw targeted lock vector!
            val isIntersecting = !survivor.isRadioSilence && (dist <= ht.detectionRadiusMeters + survivor.getRfEmissionRadiusMeters())
            if (isIntersecting) {
                canvas.drawLine(hx, hy, centerX, centerY, threatLinePaint)
                canvas.drawText("⚡ SIGINT LOCK VECTOR", (hx + centerX) / 2f, (hy + centerY) / 2f - 10f, labelPaint)
            }
        }

        // 6. Render Trapped Survivor Center Marker
        val rfRadiusMeters = survivor.getRfEmissionRadiusMeters()
        if (rfRadiusMeters > 0) {
            val rfPx = (rfRadiusMeters / scaleMetersPerPixel).toFloat()
            canvas.drawCircle(centerX, centerY, rfPx, rfFootprintPaint)
            canvas.drawText("RF EMISSION BOUNDARY (${rfRadiusMeters.roundToInt()}m)", centerX - 120f, centerY + rfPx + 24f, subLabelPaint)
        } else {
            // Dotted circle showing zero emission (stealth)
            canvas.drawText("🔇 RF RADIO SILENCE (0m FOOTPRINT)", centerX - 140f, centerY + 45f, subLabelPaint)
        }

        // Pulsing survivor core
        canvas.drawCircle(centerX, centerY, 22f, survivorPulsePaint)
        canvas.drawCircle(centerX, centerY, 12f, survivorPaint)
        canvas.drawText("SURVIVOR (YOU)", centerX - 60f, centerY - 28f, labelPaint)
    }

    fun toggleHighlightSupply(id: String): Boolean {
        val sc = simulationEngine?.supplyCaches?.find { it.id == id } ?: return false
        sc.isHighlighted = !sc.isHighlighted
        invalidate()
        return sc.isHighlighted
    }
}
