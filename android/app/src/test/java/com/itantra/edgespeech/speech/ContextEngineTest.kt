package com.itantra.edgespeech.speech

import org.junit.Assert.*
import org.junit.Test

class ContextEngineTest {

    @Test
    fun testEmergencyIntentHindi() {
        val result = ContextEngine.analyze("आपातकाल! तुरंत मदद चाहिए, चौकी 4 पर भारी फायरिंग हो रही है!")
        assertEquals(TacticalIntent.EMERGENCY_SOS, result.intent)
        assertEquals(UrgencyLevel.CRITICAL, result.urgency)
        assertTrue(result.urgencyScore >= 0.85f)
        assertTrue(result.entities.any { it.category == "LOCATION" && it.value.contains("चौकी") })
        assertTrue(result.suggestedQuickReplies.isNotEmpty())
    }

    @Test
    fun testMedicalEvacIntentEnglish() {
        val result = ContextEngine.analyze("Medevac requested immediately, 2 casualties wounded at Sector 7, doctor needed")
        assertEquals(TacticalIntent.MEDICAL_EVAC, result.intent)
        assertEquals(UrgencyLevel.CRITICAL, result.urgency)
        assertTrue(result.entities.any { it.category == "CASUALTY" && it.value.contains("2") })
        assertTrue(result.entities.any { it.category == "LOCATION" && it.value.contains("Sector") })
    }

    @Test
    fun testLogisticsSupplyIntent() {
        val result = ContextEngine.analyze("Need ammo and rations at Base Camp ASAP")
        assertEquals(TacticalIntent.LOGISTICS_SUPPLY, result.intent)
        assertTrue(result.entities.any { it.category == "LOCATION" && it.value.contains("Base") })
    }

    @Test
    fun testSituationReportHindi() {
        val result = ContextEngine.analyze("चौकी 2 पर स्थिति सामान्य है, सब ठीक और सुरक्षित है।")
        assertEquals(TacticalIntent.SITUATION_REPORT, result.intent)
        assertEquals(UrgencyLevel.LOW, result.urgency)
    }

    @Test
    fun testRouteNavigationCoordinates() {
        val result = ContextEngine.analyze("Advance towards river bridge, coordinates 45-89")
        assertEquals(TacticalIntent.ROUTE_NAVIGATION, result.intent)
        assertTrue(result.entities.any { it.category == "GRID" })
    }

    @Test
    fun testEmptyComms() {
        val result = ContextEngine.analyze("")
        assertEquals(TacticalIntent.GENERAL_COMMS, result.intent)
        assertEquals(UrgencyLevel.LOW, result.urgency)
    }
}
