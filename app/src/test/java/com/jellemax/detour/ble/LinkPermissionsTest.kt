package com.jellemax.detour.ble

import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM
import org.junit.Assert.assertEquals
import org.junit.Test

/** The display link's permissions follow the `ble-encrypted-link` flag (#837). */
class LinkPermissionsTest {

    @Test
    fun `flag off keeps the open link that ships today`() {
        assertEquals(LinkPermissions(PERMISSION_READ, PERMISSION_WRITE), linkPermissions(encrypted = false))
    }

    @Test
    fun `flag on demands a MITM-protected bond, with no plain bit left to fall back on`() {
        assertEquals(
            LinkPermissions(PERMISSION_READ_ENCRYPTED_MITM, PERMISSION_WRITE_ENCRYPTED_MITM),
            linkPermissions(encrypted = true),
        )
    }
}
