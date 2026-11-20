package com.classroom.rfdetector.scanner

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.SystemClock
import com.classroom.rfdetector.core.DeviceTracker
import com.classroom.rfdetector.core.GroupingEvaluation
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.SignalSnapshot
import com.classroom.rfdetector.core.baselineGroupCounts
import com.classroom.rfdetector.core.watchView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

/** 광고 원본 바이트를 Android ScanRecord 파서와 BleScanner 콜백에 넣어, 추적기와 감시 판정까지 그대로 통과시킨다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindMyReplayTest {
    @Before fun clockHasStarted() { ShadowSystemClock.advanceBy(Duration.ofSeconds(1)) }

    @Test fun onlyAnAppleOnlyNamelessFindMyAdvertisementIsCountedSeparately() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val scanner = BleScanner(context)
        val observations = mutableListOf<RfObservation>()
        assertTrue(scanner.start { observations.add(it) })
        val callback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val name = "ESP32-BLE".toByteArray()
        val replay = mapOf(
            // 꺼진 아이폰·에어태그와 같은 모양: 31바이트 전부가 Apple 제조사 데이터
            "5B:50:00:00:00:01" to GroupingEvaluation.FIND_MY,
            // 확장 광고에 다른 회사 코드를 덧붙인 것
            "7E:30:00:00:00:01" to GroupingEvaluation.FIND_MY + GroupingEvaluation.ESPRESSIF_ID,
            // 이름을 함께 알리는 것
            "7E:20:00:00:00:01" to GroupingEvaluation.FIND_MY + byteArrayOf((name.size + 1).toByte(), 0x09) + name,
        )
        replay.forEach { (address, bytes) ->
            val record = ReflectionHelpers.callStaticMethod<ScanRecord>(ScanRecord::class.java, "parseFromBytes", ClassParameter.from(ByteArray::class.java, bytes))
            callback.onScanResult(1, ScanResult(adapter.getRemoteDevice(address), record, -50, SystemClock.elapsedRealtimeNanos()))
        }
        scanner.stop()
        assertEquals(3, observations.size)

        val now = observations.maxOf { it.timestampMs }
        val emptyRoom = baselineGroupCounts(SignalSnapshot("A", now - 600_000, false, emptyList()))
        val tracker = DeviceTracker()
        observations.forEach(tracker::update)
        val view = watchView(tracker.snapshot(now, includeInactive = true), emptyRoom, emptySet(), now)
        assertEquals(listOf("5B:50:00:00:00:01"), view.findMy.map { it.key })
        assertEquals(setOf("7E:30:00:00:00:01", "7E:20:00:00:00:01"), view.bluetooth.flatMap { group -> group.devices.map { it.key } }.toSet())
    }
}
