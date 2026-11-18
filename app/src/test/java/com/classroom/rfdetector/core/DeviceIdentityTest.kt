package com.classroom.rfdetector.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DeviceIdentityTest {
    private val resolver = DeviceIdentityResolver(mapOf("001122" to "Registered Vendor"), mapOf(76 to "Apple, Inc.", 117 to "Samsung Electronics"))

    @Test fun macLookupWorksForUniversalUnicastOnly() {
        assertEquals("Registered Vendor", resolver.resolve("00:11:22:33:44:55", null, setOf(ScanSource.WIFI), emptySet()).macOrganization)
        assertNull(resolver.resolve("02:11:22:33:44:55", null, setOf(ScanSource.WIFI), emptySet()).macOrganization)
        assertNull(resolver.resolve("01:11:22:33:44:55", null, setOf(ScanSource.WIFI), emptySet()).macOrganization)
    }

    @Test fun randomAddressCanStillExposeAdvertisingCompany() {
        val identity = resolver.resolve("DA:11:22:33:44:55", "AirPods Pro", setOf(ScanSource.BLE), setOf(76))
        assertEquals(listOf("Apple, Inc."), identity.advertisedCompanies)
        assertNull(identity.macOrganization)
    }

    @Test fun nameMatchComesFromSpecificBleNameNotMacOrWifiSsid() {
        assertNull(resolver.resolve("00:11:22:33:44:55", null, setOf(ScanSource.BLE), setOf(76)).audioNameMatch)
        assertNull(resolver.resolve("00:11:22:33:44:55", "AirPods Pro", setOf(ScanSource.WIFI), emptySet()).audioNameMatch)
        assertEquals("Sony", resolver.resolve("sim", "Sony WF-1000XM5", setOf(ScanSource.BLE), emptySet()).audioNameMatch!!.brand)
        assertEquals("Samsung", resolver.resolve("sim", "My Galaxy Buds3 Pro", setOf(ScanSource.BLE), setOf(117)).audioNameMatch!!.brand)
    }

    @Test fun unknownModelsAndIdsAreExplicit() {
        val identity = resolver.resolve("sim", "Unknown earbuds", setOf(ScanSource.BLE), setOf(65534))
        assertNull(identity.audioNameMatch)
        assertTrue(identity.advertisedCompanies.single().contains("FFFE"))
    }

    @Test fun companyIdsSurviveAdvertisementsWithoutManufacturerData() {
        val tracker = DeviceTracker()
        tracker.update(RfObservation("device", ScanSource.BLE, null, -60, null, false, 1000, setOf(76)))
        tracker.update(RfObservation("device", ScanSource.BLE, "AirPods", -59, null, false, 1100))
        assertEquals(setOf(76), tracker.snapshot(1100).single().manufacturerIds)
    }

    @Test fun refreshedAssignmentsResolveThroughTheBundledRegistries() {
        val vendors = File("src/main/assets/mac-vendors.tsv").readLines().associate { it.substringBefore('\t') to it.substringAfter('\t') }
        val companies = File("src/main/assets/bluetooth-companies.tsv").readLines().associate { it.substringBefore('\t').toInt() to it.substringAfter('\t') }
        val bundled = DeviceIdentityResolver(vendors, companies)
        assertEquals("Microsoft Corporation", bundled.resolve("64:2F:4F:00:00:01", null, setOf(ScanSource.WIFI), emptySet()).macOrganization)
        assertNull(bundled.resolve("66:2F:4F:00:00:01", null, setOf(ScanSource.WIFI), emptySet()).macOrganization)
        assertEquals(listOf("LightingSoft AG", "AutoStoa LLC"),
            bundled.resolve("DA:11:22:33:44:55", null, setOf(ScanSource.BLE), setOf(4430, 4429)).advertisedCompanies)
    }

    @Test fun bundledOfficialRegistriesAreWellFormed() {
        val vendors = File("src/main/assets/mac-vendors.tsv").readLines().associate { it.substringBefore('\t') to it.substringAfter('\t') }
        val companies = File("src/main/assets/bluetooth-companies.tsv").readLines().associate { it.substringBefore('\t').toInt() to it.substringAfter('\t') }
        assertTrue(vendors.size >= 40_000)
        assertTrue(vendors.keys.all { it.matches(Regex("[A-F0-9]{6}")) })
        assertEquals("Apple, Inc.", companies[76])
        assertTrue(companies[117]!!.contains("Samsung"))
        assertTrue(companies.size >= 4000)
    }

    @Test fun lookupsPastTheCacheSizeStillMatchAFreshResolver() {
        val vendors = mapOf("001122" to "Registered Vendor")
        val companies = mapOf(76 to "Apple, Inc.")
        val cached = DeviceIdentityResolver(vendors, companies)
        fun query(i: Int) = Triple("00:11:22:%02X:%02X:%02X".format(i shr 16 and 0xFF, i shr 8 and 0xFF, i and 0xFF), if (i % 3 == 0) "AirPods Pro" else null, setOf(76))
        // 조회 결과를 보관하는 개수보다 많이 조회한 뒤, 맨 처음 것을 다시 조회한다.
        (0 until 5_000).forEach { i -> query(i).let { (address, name, ids) -> cached.resolve(address, name, setOf(ScanSource.BLE), ids) } }
        listOf(0, 3, 4_999).forEach { i ->
            val (address, name, ids) = query(i)
            assertEquals(DeviceIdentityResolver(vendors, companies).resolve(address, name, setOf(ScanSource.BLE), ids),
                cached.resolve(address, name, setOf(ScanSource.BLE), ids))
        }
    }

    @Test fun aChangedNameOrCompanyIdIsLookedUpAgain() {
        val first = resolver.resolve("DA:11:22:33:44:55", null, setOf(ScanSource.BLE), emptySet())
        assertNull(first.audioNameMatch)
        assertEquals("Apple", resolver.resolve("DA:11:22:33:44:55", "AirPods Pro", setOf(ScanSource.BLE), emptySet()).audioNameMatch!!.brand)
        assertEquals(listOf("Apple, Inc."), resolver.resolve("DA:11:22:33:44:55", null, setOf(ScanSource.BLE), setOf(76)).advertisedCompanies)
        assertNull(resolver.resolve("DA:11:22:33:44:55", "AirPods Pro", setOf(ScanSource.WIFI), emptySet()).audioNameMatch)
    }
}
