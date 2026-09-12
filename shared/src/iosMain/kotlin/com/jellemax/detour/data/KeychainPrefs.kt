package com.jellemax.detour.data

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * The one bag [securePrefs] needs, backed by the iOS Keychain instead of the plaintext
 * `NSUserDefaults` [UserDefaultsPrefs] used everywhere else — see #42.
 *
 * Every value is stored as its UTF-8 bytes under `kSecValueData` (a generic-password
 * item's data must be `NSData`; encoding numbers and booleans as their string form
 * avoids relying on how the framework would otherwise coerce an `NSNumber`). A missing
 * item and an item that fails to decrypt/decode both read back as "absent", which is
 * what lets [bool]/[float]/[long] tell that apart from a stored zero/false the same way
 * [UserDefaultsPrefs.has] does.
 *
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: the session is read from a
 * background refresh after first unlock, so it must not require the device to be
 * currently unlocked, and it must never sync to iCloud Keychain — a synced copy of the
 * session/refresh token would be exactly the cross-device leak
 * `kSecAttrSynchronizable = false` (the default this project relies on rather than sets
 * explicitly) exists to avoid.
 *
 * Keychain items outlive an uninstall, unlike `NSUserDefaults`. [Auth]'s sign-out path
 * removing every key it wrote (rather than only clearing in-memory state) is what makes
 * a reinstall start from a clean session instead of silently recovering an old one.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KeychainPrefs(private val service: String = "com.jellemax.detour.secure") : Prefs {

    override fun string(key: String, def: String): String = readString(key) ?: def

    override fun bool(key: String, def: Boolean): Boolean =
        readString(key)?.let { it == "true" } ?: def

    override fun float(key: String, def: Float): Float =
        readString(key)?.toFloatOrNull() ?: def

    override fun long(key: String, def: Long): Long =
        readString(key)?.toLongOrNull() ?: def

    override fun put(key: String, value: String) = write(key, value)
    override fun put(key: String, value: Boolean) = write(key, value.toString())
    override fun put(key: String, value: Float) = write(key, value.toString())
    override fun put(key: String, value: Long) = write(key, value.toString())

    override fun remove(key: String) {
        SecItemDelete(query(key))
    }

    /** Only this service's items — the Keychain is shared with every app on the
     *  device, so a query with no `kSecAttrService` would delete everyone's. */
    override fun clear() {
        val q = CFDictionaryCreateMutable(null, 0, null, null)
        CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(q, kSecAttrService, CFBridgingRetain(service))
        SecItemDelete(q)
    }

    private fun query(key: String): CFMutableDictionaryRef? {
        val q = CFDictionaryCreateMutable(null, 0, null, null)
        CFDictionaryAddValue(q, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(q, kSecAttrService, CFBridgingRetain(service))
        CFDictionaryAddValue(q, kSecAttrAccount, CFBridgingRetain(key))
        return q
    }

    /** Delete-then-add rather than `SecItemUpdate`: a query dictionary cannot carry
     *  `kSecAttrAccessible` on update (the framework refuses to change it after the
     *  fact), and this bag has no read-modify-write caller that would care about the
     *  extra round trip. */
    private fun write(key: String, value: String) {
        val data = value.encodeToByteArray().toNSData()
        SecItemDelete(query(key))
        val insert = query(key)
        CFDictionaryAddValue(insert, kSecValueData, CFBridgingRetain(data))
        CFDictionaryAddValue(insert, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        SecItemAdd(insert, null)
    }

    private fun readString(key: String): String? = memScoped {
        val q = query(key)
        CFDictionaryAddValue(q, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(q, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(q, result.ptr)
        if (status != errSecSuccess) return@memScoped null
        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        data.toByteArray().decodeToString()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    val mutable = NSMutableData()
    if (isNotEmpty()) {
        usePinned { pinned -> mutable.appendBytes(pinned.addressOf(0), size.convert()) }
    }
    return mutable
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    return bytes!!.reinterpret<ByteVar>().readBytes(len)
}
