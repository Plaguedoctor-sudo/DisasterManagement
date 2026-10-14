package network.disaster.management

import network.disaster.management.localai.OnDeviceTacticalAdvisor
import network.disaster.management.map.DisasterIncidentType
import network.disaster.management.map.MapIncident
import network.disaster.management.map.TacticalPoint
import network.disaster.management.model.FeedMessage
import network.disaster.management.model.MessageType
import org.junit.Assert.*
import org.junit.Test

class OnDeviceTacticalAdvisorTest {

    private val advisor = OnDeviceTacticalAdvisor()

    @Test
    fun testOnDeviceTriageDistressRed() {
        val triage = advisor.triageDistress("RED ALERT: Trapped civilian with massive arterial bleeding from leg")
        assertEquals("RED", triage.priorityCode)
        assertEquals(10, triage.urgencyScore)
        assertTrue(triage.requiresEvacuation)
        assertTrue(triage.immediateActions.any { it.contains("tourniquet", ignoreCase = true) })
        assertTrue(triage.suggestedSupplies.any { it.contains("CAT Tourniquet", ignoreCase = true) })
    }

    @Test
    fun testOnDeviceTriageDistressYellow() {
        val triage = advisor.triageDistress("Civilian suffered broken leg bone fracture and second degree thermal burn")
        assertEquals("YELLOW", triage.priorityCode)
        assertEquals(7, triage.urgencyScore)
        assertTrue(triage.requiresEvacuation)
        assertTrue(triage.immediateActions.any { it.contains("splint", ignoreCase = true) })
    }

    @Test
    fun testOnDeviceTriageDistressGreen() {
        val triage = advisor.triageDistress("All family members safe and sheltered at home, no injuries")
        assertEquals("GREEN", triage.priorityCode)
        assertEquals(2, triage.urgencyScore)
        assertFalse(triage.requiresEvacuation)
        assertTrue(triage.immediateActions.any { it.contains("sheltered", ignoreCase = true) })
    }

    @Test
    fun testOnDeviceSitrepGeneration() {
        val messages = listOf(
            FeedMessage(
                id = "1",
                groupId = "GLOBAL",
                senderName = "Peer-1",
                content = "CRITICAL SOS: Bleeding survivor trapped in rubble",
                type = MessageType.EMERGENCY,
                isEmergency = true
            ),
            FeedMessage(
                id = "2",
                groupId = "GLOBAL",
                senderName = "Peer-2",
                content = "STATUS: SAFE at community shelter",
                type = MessageType.TEXT
            )
        )

        val incidents = listOf(
            MapIncident(
                id = "inc-1",
                type = DisasterIncidentType.HAZARD_ALERT,
                title = "Collapsed Overpass",
                senderName = "Scout-A",
                location = TacticalPoint(37.77, -122.41),
                timestamp = System.currentTimeMillis(),
                details = "Concrete debris blocking main evacuation route"
            )
        )

        val sitrep = advisor.generateSitrep(messages, incidents, 2)
        assertNotNull(sitrep)
        assertTrue(sitrep.rawBriefing.contains("ON-DEVICE CIVIL DEFENSE SITREP"))
        assertTrue(sitrep.rawBriefing.contains("STRICT AIR-GAP"))
        assertEquals(1, sitrep.totalDistressSignals)
        assertEquals(1, sitrep.severeCasualties)
        assertTrue(sitrep.rawBriefing.contains("Collapsed Overpass"))
    }

    @Test
    fun testOnDeviceEmergencyFieldManual() {
        val water = advisor.queryEmergencyAdvisor("how to purify drinking water")
        assertTrue(water.contains("BOILING") || water.contains("BLEACH"))

        val bleed = advisor.queryEmergencyAdvisor("victim has severe bleeding")
        assertTrue(bleed.contains("TOURNIQUET") && bleed.contains("DIRECT PRESSURE"))

        val collapse = advisor.queryEmergencyAdvisor("trapped under building collapse rubble")
        assertTrue(collapse.contains("TAP") || collapse.contains("SIGNALING"))
        assertTrue(collapse.contains("AIRWAY"))

        val burns = advisor.queryEmergencyAdvisor("thermal flame burn treatment")
        assertTrue(burns.contains("COOL") && burns.contains("water", ignoreCase = true))

        val smoke = advisor.queryEmergencyAdvisor("smoke and gas plume in building")
        assertTrue(smoke.contains("CRAWL") || smoke.contains("LOW"))

        val cold = advisor.queryEmergencyAdvisor("severe hypothermia frostbite")
        assertTrue(cold.contains("WARM") && cold.contains("CORE"))

        val fracture = advisor.queryEmergencyAdvisor("compound broken bone fracture")
        assertTrue(fracture.contains("SPLINT") || fracture.contains("IMMOBILIZE"))
    }

    @Test
    fun testTacticalBearingAndCardinals() {
        val pOrigin = TacticalPoint(0.0, 0.0)
        val pNorth = TacticalPoint(1.0, 0.0)
        val pEast = TacticalPoint(0.0, 1.0)
        val pSouth = TacticalPoint(-1.0, 0.0)
        val pWest = TacticalPoint(0.0, -1.0)

        val (_, cardN) = advisor.calculateBearing(pOrigin, pNorth)
        assertEquals("N", cardN)

        val (_, cardE) = advisor.calculateBearing(pOrigin, pEast)
        assertEquals("E", cardE)

        val (_, cardS) = advisor.calculateBearing(pOrigin, pSouth)
        assertEquals("S", cardS)

        val (_, cardW) = advisor.calculateBearing(pOrigin, pWest)
        assertEquals("W", cardW)
    }

    @Test
    fun testTacticalGridEncoding() {
        val grid = advisor.computeTacticalGrid(37.7749, -122.4194)
        assertTrue(grid.startsWith("GRID-"))
        assertTrue(grid.contains("-"))
    }
}
