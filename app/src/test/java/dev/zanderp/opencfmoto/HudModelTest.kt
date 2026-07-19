package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure HUD model — geometry, formatting, layout ([HudModel.kt]). */
class HudModelTest {

    // ── bar detection ────────────────────────────────────────────────────────────────────────────

    @Test fun fit_side_bars_on_800mt_geometry() {
        // 800MT: AA 800x480 fit into a 1280x576 canvas → centered 960x576 → 160px side bars.
        val bars = HudBars.detect(1280, 576, vpX = 160, vpY = 0, vpW = 960, vpH = 576, ScreenFit.FIT, minThickness = 48)
        assertEquals(2, bars.size)
        assertEquals(BarRect(0, 0, 160, 576), bars[0])       // left
        assertEquals(BarRect(1120, 0, 160, 576), bars[1])    // right
        assertTrue(bars[0].vertical)
    }

    @Test fun fit_top_bottom_bars() {
        // AA shorter than canvas → top/bottom bars.
        val bars = HudBars.detect(800, 600, vpX = 0, vpY = 60, vpW = 800, vpH = 480, ScreenFit.FIT, minThickness = 48)
        assertEquals(2, bars.size)
        assertEquals(BarRect(0, 0, 800, 60), bars[0])        // top
        assertEquals(BarRect(0, 540, 800, 60), bars[1])      // bottom
        assertFalse(bars[0].vertical)
    }

    @Test fun fill_and_stretch_have_no_bars() {
        assertTrue(HudBars.detect(1280, 576, 160, 0, 960, 576, ScreenFit.FILL, 48).isEmpty())
        assertTrue(HudBars.detect(1280, 576, 0, 0, 1280, 576, ScreenFit.STRETCH, 48).isEmpty())
    }

    @Test fun thin_bars_below_min_thickness_are_skipped() {
        // 20px side bars, min 48 → nothing legible, no HUD.
        val bars = HudBars.detect(1000, 576, vpX = 20, vpY = 0, vpW = 960, vpH = 576, ScreenFit.FIT, minThickness = 48)
        assertTrue(bars.isEmpty())
    }

    @Test fun matched_aspect_yields_no_bars() {
        val bars = HudBars.detect(960, 576, vpX = 0, vpY = 0, vpW = 960, vpH = 576, ScreenFit.FIT, minThickness = 48)
        assertTrue(bars.isEmpty())
    }

    // ── formatting ───────────────────────────────────────────────────────────────────────────────

    @Test fun speed_no_fix_is_dashes() {
        assertEquals("--", HudFormat.speed(RideStats(speedKmh = 40, hasFix = false), SpeedUnit.KMH))
    }

    @Test fun speed_units() {
        val s = RideStats(speedKmh = 100, hasFix = true)
        assertEquals("100", HudFormat.speed(s, SpeedUnit.KMH))
        assertEquals("62", HudFormat.speed(s, SpeedUnit.MPH)) // 100 km/h ≈ 62 mph
    }

    @Test fun distance_formatting() {
        assertEquals("2.5 km", HudFormat.distance(2500.0, SpeedUnit.KMH))
        assertEquals("42 km", HudFormat.distance(42_000.0, SpeedUnit.KMH))
        assertEquals("1.6 mi", HudFormat.distance(2500.0, SpeedUnit.MPH))
    }

    @Test fun duration_formatting() {
        assertEquals("0:05", HudFormat.duration(5_000))
        assertEquals("1:05", HudFormat.duration(65_000))
        assertEquals("1:01:05", HudFormat.duration(3_665_000))
    }

    @Test fun battery_formatting() {
        assertEquals("84%", HudFormat.battery(84, charging = false))
        assertEquals("84%+", HudFormat.battery(84, charging = true))
        assertEquals("--", HudFormat.battery(-1, charging = false))
    }

    // ── stats + layout ───────────────────────────────────────────────────────────────────────────

    @Test fun hot_flag_tracks_thermal_status() {
        assertFalse(RideStats(thermalStatus = 1).hot)   // light
        assertTrue(RideStats(thermalStatus = 2).hot)    // moderate
        assertTrue(RideStats(thermalStatus = 4).hot)    // critical
    }

    @Test fun cells_hero_speed_first_and_temp_only_when_hot() {
        val cool = HudLayout.cells(RideStats(speedKmh = 50, hasFix = true, thermalStatus = 0), SpeedUnit.KMH, "14:05")
        assertTrue(cool.first().big)
        assertEquals("50", cool.first().value)
        assertFalse(cool.any { it.label == "temp" })

        val hot = HudLayout.cells(RideStats(speedKmh = 50, hasFix = true, thermalStatus = 3), SpeedUnit.KMH, "14:05")
        assertTrue(hot.any { it.label == "temp" && it.value == "HOT" })
    }

    @Test fun distribute_splits_two_bars_evenly_speed_in_first() {
        val cells = HudLayout.cells(RideStats(speedKmh = 50, hasFix = true), SpeedUnit.KMH, "14:05") // 5 cells
        val split = HudLayout.distribute(cells, 2)
        assertEquals(2, split.size)
        assertTrue(split[0].first().big)          // speed in the first bar
        assertEquals(cells.size, split.sumOf { it.size })  // nothing lost
    }

    @Test fun distribute_single_bar_keeps_all() {
        val cells = HudLayout.cells(RideStats(hasFix = true), SpeedUnit.KMH, "14:05")
        assertEquals(listOf(cells), HudLayout.distribute(cells, 1))
    }

    @Test fun cells_respects_enabled_elements() {
        val stats = RideStats(speedKmh = 50, hasFix = true, batteryPct = 80)
        val onlySpeed = HudLayout.cells(stats, SpeedUnit.KMH, "14:05", setOf(HudElement.SPEED))
        assertEquals(1, onlySpeed.size)
        assertTrue(onlySpeed.first().big)

        val speedAndBattery = HudLayout.cells(stats, SpeedUnit.KMH, "14:05", setOf(HudElement.SPEED, HudElement.BATTERY))
        assertEquals(2, speedAndBattery.size)
        assertTrue(speedAndBattery.any { it.label == "phone" })
        assertFalse(speedAndBattery.any { it.label == "clock" })
    }

    @Test fun temp_needs_both_enabled_and_hot() {
        val hot = RideStats(hasFix = true, thermalStatus = 3)
        assertFalse(HudLayout.cells(hot, SpeedUnit.KMH, "14:05", setOf(HudElement.SPEED)).any { it.label == "temp" })
        assertTrue(HudLayout.cells(hot, SpeedUnit.KMH, "14:05", setOf(HudElement.TEMP)).any { it.label == "temp" })
        val cool = RideStats(hasFix = true, thermalStatus = 0)
        assertFalse(HudLayout.cells(cool, SpeedUnit.KMH, "14:05", setOf(HudElement.TEMP)).any { it.label == "temp" })
    }

    @Test fun empty_enabled_set_yields_no_cells() {
        assertTrue(HudLayout.cells(RideStats(hasFix = true), SpeedUnit.KMH, "14:05", emptySet()).isEmpty())
    }

    @Test fun signature_changes_with_content() {
        val a = HudLayout.cells(RideStats(speedKmh = 50, hasFix = true), SpeedUnit.KMH, "14:05")
        val b = HudLayout.cells(RideStats(speedKmh = 51, hasFix = true), SpeedUnit.KMH, "14:05")
        assertEquals(HudLayout.signature(a), HudLayout.signature(a))
        assertTrue(HudLayout.signature(a) != HudLayout.signature(b))
    }
}
