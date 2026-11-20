package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 감시 중 새로 잡혔는데도 "원래 있던 전파"로 넘어가거나 개수만 세는 경우의 지금 동작이다. 지켜야 할 요구 사항이 아니다.
 * 각 테스트 위에 정해진 규칙인지, 다른 규칙의 부수 효과인지 적었다. 규칙을 바꾸는 PR은 기대값을 고치면 된다.
 */
class SilentlyKnownCasesTest {
    private val tracker = DeviceTracker()
    private val school = "00:11:22:33:44:10"

    private fun wifi(address: String, atMs: Long, name: String?, rssi: Int = -50) =
        tracker.update(RfObservation(address, ScanSource.WIFI, name, rssi, null, name == null, atMs))

    private fun ble(address: String, atMs: Long, name: String? = null, rssi: Int = -50, ids: Set<Int> = setOf(76), hints: Set<SignalHint> = emptySet()) =
        tracker.update(RfObservation(address, ScanSource.BLE, name, rssi, null, false, atMs, ids, hints))

    private fun emptyRoom(nowMs: Long) = SignalSnapshot.capture("A", nowMs, false, tracker.snapshot(nowMs, includeInactive = true))

    /** AppViewModel.refresh()처럼 빈 교실 와이파이 주소와 대표 목록까지 넘겨 감시 화면을 만든다. 확인함은 없다. */
    private fun watch(record: SignalSnapshot, nowMs: Long) = watchView(tracker.snapshot(nowMs, includeInactive = true),
        baselineGroupCounts(record), emptySet(), nowMs, baselineWifiAddresses(record), baselineSamples(record))

    private fun schoolRoom(): SignalSnapshot {
        wifi(school, 0, "SCHOOL")
        return emptyRoom(1_000)
    }

    // 정해진 규칙(upstream #7에서 유지). 이름을 보지 않는다는 점은 따로 논의된 적이 없다.
    @Test fun anyNameOnAnAddressDifferingOnlyInTheFirstOrLastByteIsKnown() {
        val record = schoolRoom()
        // 이름이 전혀 다르고 신호가 세도, 마지막 자리만 다른 주소는 같은 공유기로 본다(isSameAccessPoint).
        wifi("00:11:22:33:44:11", 600_000, "CAM-7F21", rssi = -35)
        wifi("00:11:22:33:44:12", 600_000, null, rssi = -35)
        // 첫 자리만 다른 주소도 마찬가지다.
        wifi("06:11:22:33:44:10", 600_000, "AndroidAP", rssi = -35)
        val view = watch(record, 601_000)
        assertEquals(0, view.count)
        assertTrue(view.knownGroups.any { group -> group.live.any { it.key == "00:11:22:33:44:11" } })
    }

    // README에 적힌 한계. upstream #42가 바꾸려는 동작이다.
    @Test fun theSameNameOnAnUnrelatedUniversalAddressIsKnown() {
        val record = schoolRoom()
        // 다른 회사 번호를 가진 고유 주소. 이름만 같으면 원래 있던 와이파이로 본다.
        wifi("3C:22:FB:01:02:03", 600_000, "SCHOOL", rssi = -35)
        assertEquals(0, watch(record, 601_000).count)
    }

    // 부수 효과. 이름 묶음 키를 만들 때 생기는 것으로, 와이파이에 대해 정한 적은 없다.
    @Test fun nameComparisonIgnoresCaseAndSurroundingSpaces() {
        val record = schoolRoom()
        // 와이파이 이름은 바이트 그대로 비교되는 값인데, 묶음 키는 소문자로 바꾸고 앞뒤 공백을 지운다.
        wifi("3C:22:FB:01:02:03", 600_000, "school ", rssi = -35)
        assertEquals(0, watch(record, 601_000).count)
    }

    // 정해진 규칙(upstream #45). 둘째·셋째 자리 예외는 실측 근거가 없는 값이다.
    @Test fun theSameNameOnARandomAddressIsFlaggedUnlessItSharesTheRoutersSecondAndThirdOctets() {
        val record = schoolRoom()
        // 임의 주소로 이름을 따라 하면 알린다(#45).
        wifi("DA:01:02:03:04:05", 600_000, "SCHOOL", rssi = -35)
        val flagged = watch(record, 601_000).wifi.single()
        assertTrue(flagged.copiesEmptyRoomName)
        // 둘째·셋째 자리를 빈 교실 공유기와 맞춘 임의 주소는 같은 회사 공유기로 보고 넘긴다(sharesVendorWithEmptyRoomRouter).
        wifi("DA:11:22:99:88:77", 600_000, "SCHOOL", rssi = -35)
        assertEquals(listOf("DA:01:02:03:04:05"), watch(record, 601_000).wifi.single().devices.map { it.key })
    }

    // 정해진 규칙(꺼서 제출한 아이폰). 실제 오프라인 찾기 광고처럼 Apple 회사 코드 하나에 이름이 없으면 흉내 낸 것도 구분하지 못한다.
    @Test fun findMyFormatIsCountedButNeverAlertedWhateverTheCountOrStrength() {
        ble("AA:00:00:00:00:01", 0, hints = setOf(SignalHint.APPLE_ACTIVE))
        val record = emptyRoom(1_000)
        // 빈 교실에 없던 나의 찾기 형식 광고 다섯 개가 아주 세게 2분 동안 잡힌다.
        (0..12).forEach { step ->
            (1..5).forEach { i -> ble("CC:00:00:00:00:0$i", 600_000L + step * 10_000, rssi = -35, hints = setOf(SignalHint.APPLE_FIND_MY)) }
        }
        val view = watch(record, 721_000)
        assertEquals(0, view.count)
        assertEquals(0, view.far.size)
        assertEquals(5, view.findMy.size)
    }

    // 오프라인 찾기 광고는 31바이트를 Apple 제조사 데이터 하나로 다 쓴다. 다른 회사 코드나 이름이 같이 오면 그 광고가 아니다.
    @Test fun aFindMyFrameWithAnotherCompanyIdOrANameIsJudgedLikeAnyOtherDevice() {
        val record = emptyRoom(0)
        ble("CC:00:00:00:00:09", 600_000, rssi = -35, ids = setOf(76, 0x02E5), hints = setOf(SignalHint.APPLE_FIND_MY))
        ble("CC:00:00:00:00:0A", 600_000, name = "ESP32-BLE", rssi = -35, hints = setOf(SignalHint.APPLE_FIND_MY))
        val view = watch(record, 601_000)
        assertEquals(0, view.findMy.size)
        assertEquals(setOf("CC:00:00:00:00:09", "CC:00:00:00:00:0A"), view.bluetooth.flatMap { group -> group.devices.map { it.key } }.toSet())
    }

    // 설계 문서의 알려진 한계("같은 생김새를 맞바꾸면 알 수 없다").
    @Test fun aDeviceThatTakesOverAnEmptyRoomNameAfterTheOriginalLeavesIsKnown() {
        ble("AA:00:00:00:00:01", 0, name = "Galaxy Buds2 Pro", ids = setOf(117))
        val record = emptyRoom(1_000)
        // 감독관의 이어폰은 꺼졌고, 같은 이름을 쓰는 다른 회사 코드의 기기가 2분 동안 세게 잡힌다.
        (0..12).forEach { step -> ble("BB:00:00:00:00:01", 600_000L + step * 10_000, name = "Galaxy Buds2 Pro", rssi = -35, ids = setOf(0xFFFF)) }
        assertEquals(0, watch(record, 721_000).count)
    }

    // 대조군: 원래 기기가 남아 있으면 개수 비교로 잡힌다.
    @Test fun theSameTakeoverIsFlaggedWhileTheOriginalIsStillPresent() {
        ble("AA:00:00:00:00:01", 0, name = "Galaxy Buds2 Pro", ids = setOf(117))
        val record = emptyRoom(1_000)
        (0..6).forEach { step ->
            ble("AA:00:00:00:00:01", 600_000L + step * 10_000, name = "Galaxy Buds2 Pro", ids = setOf(117))
            ble("BB:00:00:00:00:01", 600_000L + step * 10_000, name = "Galaxy Buds2 Pro", rssi = -35, ids = setOf(0xFFFF))
        }
        val group = watch(record, 661_000).bluetooth.single()
        assertEquals(1, group.baselineCount)
        assertEquals(2, group.devices.size)
    }
}
