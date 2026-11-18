package com.classroom.rfdetector.core

/** Every identity field retains its source; advertising names and IDs are self-reported. */
data class DeviceIdentity(
    val advertisedCompanies: List<String> = emptyList(),
    val macOrganization: String? = null,
    val audioNameMatch: AudioNameMatch? = null,
    val wearableNameMatch: WearableNameMatch? = null,
) {
    val summary: String get() = advertisedCompanies.firstOrNull()?.let { "$it · BLE 광고" }
        ?: macOrganization?.let { "$it · MAC 등록사" }
        ?: audioNameMatch?.let { "${it.brand} · 이름 기반 후보" }
        ?: wearableNameMatch?.let { "${it.brand} · 이름 기반 후보" } ?: "제조사 미확인"
}

class DeviceIdentityResolver(
    private val macVendors: Map<String, String>,
    private val bluetoothCompanies: Map<Int, String>,
) {
    private val macPattern = Regex("[0-9A-F]{12}")

    private data class Query(val address: String, val name: String?, val sources: Set<ScanSource>, val manufacturerIds: Set<Int>)

    /** 화면 갱신마다 지금까지 잡힌 주소 전부를 다시 조회한다. 결과는 입력에만 달려 있으므로 같은 입력은 한 번만 계산한다. */
    private val cache = object : LinkedHashMap<Query, DeviceIdentity>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Query, DeviceIdentity>) = size > CACHE_SIZE
    }

    @Synchronized
    fun resolve(address: String, name: String?, sources: Set<ScanSource>, manufacturerIds: Set<Int>): DeviceIdentity =
        cache.getOrPut(Query(address, name, sources, manufacturerIds)) { lookup(address, name, sources, manufacturerIds) }

    private fun lookup(address: String, name: String?, sources: Set<ScanSource>, manufacturerIds: Set<Int>): DeviceIdentity {
        val mac = address.replace(":", "").replace("-", "").uppercase()
        val validMac = mac.matches(macPattern)
        val firstOctet = if (validMac) mac.take(2).toInt(16) else 0
        val local = validMac && firstOctet and 2 != 0
        val unicast = validMac && firstOctet and 1 == 0
        val organization = if (unicast && !local) macVendors[mac.take(6)] else null
        val orderedIds = manufacturerIds.sortedWith(compareBy<Int> { it !in bluetoothCompanies }.thenBy { it })
        val companies = orderedIds.map { bluetoothCompanies[it] ?: "미등록 회사 ID 0x${it.toString(16).uppercase().padStart(4, '0')}" }
        val audio = if (ScanSource.BLE in sources) AudioNameCatalog.match(name) else null
        val wearable = if (ScanSource.BLE in sources) WearableNameCatalog.match(name) else null
        return DeviceIdentity(companies, organization, audio, wearable)
    }

    private companion object {
        const val CACHE_SIZE = 4096
    }
}
