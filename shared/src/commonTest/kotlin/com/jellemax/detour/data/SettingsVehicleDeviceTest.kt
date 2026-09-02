package com.jellemax.detour.data

import com.jellemax.detour.drive.FuelType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Characterises the pure JSON encode/decode [Settings] uses for
 *  [Settings.VehicleDevice] — extracted so the OBD2 field's round trip and
 *  backward-compat default are testable without a real [Prefs] backend. */
class SettingsVehicleDeviceTest {

    @Test
    fun obd2AddressRoundTripsThroughEncodeAndDecode() {
        val device = Settings.VehicleDevice(
            address = "AA:BB:CC:DD:EE:FF", name = "My Car", mode = TravelMode.CAR,
            obd2Address = "11:22:33:44:55:66",
        )
        val decoded = Settings.decodeVehicleDevice(device.address, Settings.encodeVehicleDevice(device))
        assertEquals(device, decoded)
    }

    @Test
    fun aDeviceWithNoObd2AdapterDecodesWithNullObd2Address() {
        val device = Settings.VehicleDevice("AA:BB:CC:DD:EE:FF", "My Car", TravelMode.CAR)
        val decoded = Settings.decodeVehicleDevice(device.address, Settings.encodeVehicleDevice(device))
        assertNull(decoded.obd2Address)
    }

    @Test
    fun anEntrySavedBeforeObd2ExistedDecodesWithNullObd2Address() {
        // New-format entry from before this field existed: {mode, name}, no obd2Address key.
        val old: JsonObject = buildJsonObject {
            put("mode", TravelMode.MOTO.name)
            put("name", "My Bike")
        }
        val decoded = Settings.decodeVehicleDevice("11:22:33", old)
        assertEquals(Settings.VehicleDevice("11:22:33", "My Bike", TravelMode.MOTO, null), decoded)
    }

    @Test
    fun theOldestFormatWithNoNameOrObd2AddressDecodesUsingTheAddressAsName() {
        // Oldest format (v1.24): {address: "MODE"} as a bare JSON string, not an object.
        val decoded = Settings.decodeVehicleDevice("11:22:33", JsonPrimitive("CAR"))
        assertEquals(Settings.VehicleDevice("11:22:33", "11:22:33", TravelMode.CAR, null), decoded)
    }

    @Test
    fun fuelTypeAndCalibrationRoundTrip() {
        val device = Settings.VehicleDevice(
            "AA:BB:CC:DD:EE:FF", "TDI", TravelMode.CAR,
            obd2Address = "11:22:33:44:55:66",
            fuelType = FuelType.DIESEL, fuelCalibrationPct = 108,
        )
        val decoded = Settings.decodeVehicleDevice(device.address, Settings.encodeVehicleDevice(device))
        assertEquals(device, decoded)
    }

    @Test
    fun petrolAndDefaultCalibrationAreNotWrittenToJson() {
        val device = Settings.VehicleDevice("AA:BB", "Car", TravelMode.CAR)
        val json = Settings.encodeVehicleDevice(device)
        assertNull(json["fuelType"])
        assertNull(json["fuelCalibrationPct"])
    }

    @Test
    fun anEntryWithNoFuelKeysDecodesAsPetrolAt100() {
        val old: JsonObject = buildJsonObject {
            put("mode", TravelMode.CAR.name)
            put("name", "Old Car")
            put("obd2Address", "11:22:33")
        }
        val decoded = Settings.decodeVehicleDevice("AA:BB", old)
        assertEquals(FuelType.PETROL, decoded.fuelType)
        assertEquals(100, decoded.fuelCalibrationPct)
    }

    @Test
    fun anUnknownFuelTypeStringDecodesAsPetrol() {
        val bad: JsonObject = buildJsonObject {
            put("mode", TravelMode.CAR.name); put("name", "Car"); put("fuelType", "LPG")
        }
        assertEquals(FuelType.PETROL, Settings.decodeVehicleDevice("AA:BB", bad).fuelType)
    }

    @Test
    fun anOutOfRangeCalibrationDecodesClampedToTheBounds() {
        fun decodedPct(raw: Int): Int {
            val j = buildJsonObject {
                put("mode", TravelMode.CAR.name); put("name", "Car"); put("fuelCalibrationPct", raw)
            }
            return Settings.decodeVehicleDevice("AA:BB", j).fuelCalibrationPct
        }
        assertEquals(Settings.FUEL_CALIBRATION_MIN, decodedPct(10))
        assertEquals(Settings.FUEL_CALIBRATION_MAX, decodedPct(500))
        assertEquals(100, decodedPct(100))
    }
}
