package com.classroom.rfdetector.core

import java.util.Random

/**
 * 묶음·알림 판정 평가. 시나리오마다 교실의 실제 상황(어떤 기기가 새로 들어왔는지)을 정해 두고, 관측값을 앱과 같은 순서로
 * 넣는다: 하나의 DeviceTracker로 2분 측정 → SignalSnapshot.capture → 10분 감시, 5초마다 watchView.
 * 판정 규칙은 전부 운영 코드(signalGroupKey, watchView, bleHints, wifiHints)를 그대로 쓴다.
 */
object GroupingEvaluation {
    const val COLLECT_MS = 120_000L
    const val SAVE_AT_MS = 121_000L
    const val WATCH_MS = 600_000L
    const val TICK_MS = 5_000L

    /** 이 송신기가 교실 기준으로 무엇인지. 관측 주소가 새것인지, 묶음이 새것인지와는 따로 정한다. */
    enum class Truth {
        /** 빈 교실 때부터 있던 기기(주소가 바뀌거나 측정 때 놓친 주소라도). 감시 목록에 뜨면 오탐. */
        FAMILIAR,
        /** 시험 중 교실 안에 새로 들어온 기기. 감시 목록에 한 번도 뜨지 않으면 놓침. */
        NEW_IN_ROOM,
        /** 교실 밖의 새 기기. 앱이 안팎을 구분하지 못하므로 결과만 기록한다. */
        OUTSIDE_ROOM,
        /** 꺼서 제출한 아이폰의 나의 찾기 신호. 알리지 않는 것이 정해진 동작. */
        SUBMITTED_OFF,
    }

    class Emitter(val label: String, val truth: Truth, val observations: (Random) -> List<RfObservation>)

    class Scenario(val family: String, val label: String, val target: Emitter?)

    data class EmitterResult(
        val label: String,
        val truth: Truth,
        /** 빈 교실 기록에 없던 주소가 하나라도 있었는지. */
        val newObservation: Boolean,
        /** 빈 교실 기록에 없던 묶음 키가 하나라도 있었는지(운영 코드의 groupKey 기준). */
        val newGroup: Boolean,
        val liveTicks: Int,
        val listedTicks: Int,
        val farTicks: Int,
        val findMyTicks: Int,
        val firstListedMs: Long?,
    ) {
        val listed: Boolean get() = listedTicks > 0
        /** 목록에 30초(6회) 넘게 있었는지. 한두 번 깜빡인 것과 계속 뜬 것을 나눠 본다. */
        val sustained: Boolean get() = listedTicks >= 6
        val wrong: Boolean get() = when (truth) {
            Truth.NEW_IN_ROOM -> !listed
            Truth.FAMILIAR, Truth.SUBMITTED_OFF -> listed
            Truth.OUTSIDE_ROOM -> false
        }
    }

    data class RunResult(val scenario: Scenario, val seed: Long, val target: EmitterResult?, val background: List<EmitterResult>)

    fun run(scenario: Scenario, seed: Long): RunResult {
        val random = Random(seed)
        val emitters = background() + listOfNotNull(scenario.target)
        val byEmitter = emitters.associateWith { it.observations(Random(random.nextLong())) }
        val owner = byEmitter.flatMap { (emitter, list) -> list.map { radioKey(it.source, it.key) to emitter } }.toMap()
        val all = byEmitter.values.flatten().sortedBy { it.timestampMs }
        val tracker = DeviceTracker()
        var index = 0
        fun feedUntil(timeMs: Long) {
            while (index < all.size && all[index].timestampMs <= timeMs) tracker.update(all[index++])
        }
        feedUntil(SAVE_AT_MS)
        val record = SignalSnapshot.capture("eval", SAVE_AT_MS, false, tracker.snapshot(SAVE_AT_MS, includeInactive = true))
        val counts = baselineGroupCounts(record)
        val wifi = baselineWifiAddresses(record)
        val samples = baselineSamples(record)
        val recorded = record.signals.map { radioKey(it.source, it.address) }.toSet()

        class Tally { var live = 0; var listed = 0; var far = 0; var findMy = 0; var first: Long? = null }
        val tallies = emitters.associateWith { Tally() }
        var now = SAVE_AT_MS + TICK_MS
        while (now <= SAVE_AT_MS + WATCH_MS) {
            feedUntil(now)
            val devices = tracker.snapshot(now, includeInactive = true)
            val view = watchView(devices, counts, emptySet(), now, wifi, samples)
            // 개수 초과로 뜬 묶음에는 원래 있던 기기도 함께 들어간다. 묶음 안에 새 기기가 있으면 그 기기만 뜬 것으로 세고,
            // 새 기기 없이 뜬 묶음은 그 안의 원래 기기 모두를 오탐으로 센다.
            val listedKeys = (view.wifi + view.bluetooth).flatMap { group ->
                val keys = group.devices.map { it.trackingKey }
                val newer = keys.filter { owner[it]?.truth == Truth.NEW_IN_ROOM || owner[it]?.truth == Truth.OUTSIDE_ROOM }
                newer.ifEmpty { keys }
            }.toSet()
            val farKeys = view.far.flatMap { group -> group.devices.map { it.trackingKey } }.toSet()
            val findMyKeys = view.findMy.map { it.trackingKey }.toSet()
            devices.filter { it.presence != SignalPresence.HISTORY }.groupBy { owner[it.trackingKey] }.forEach { (emitter, live) ->
                val tally = tallies[emitter ?: return@forEach] ?: return@forEach
                tally.live++
                if (live.any { it.trackingKey in listedKeys }) { tally.listed++; if (tally.first == null) tally.first = now }
                if (live.any { it.trackingKey in farKeys }) tally.far++
                if (live.any { it.trackingKey in findMyKeys }) tally.findMy++
            }
            now += TICK_MS
        }
        val finalDevices = tracker.snapshot(now, includeInactive = true).groupBy { owner[it.trackingKey] }
        fun result(emitter: Emitter): EmitterResult {
            val tally = tallies.getValue(emitter)
            val own = finalDevices[emitter].orEmpty()
            val start = byEmitter.getValue(emitter).filter { it.timestampMs > SAVE_AT_MS }.minOfOrNull { it.timestampMs }
            return EmitterResult(emitter.label, emitter.truth,
                newObservation = own.any { it.trackingKey !in recorded },
                newGroup = own.any { it.groupKey !in counts },
                liveTicks = tally.live, listedTicks = tally.listed, farTicks = tally.far, findMyTicks = tally.findMy,
                firstListedMs = tally.first?.let { first -> start?.let { first - it } })
        }
        return RunResult(scenario, seed, scenario.target?.let(::result), emitters.filter { it !== scenario.target }.map(::result))
    }

    // ---- 관측값 만들기 ----

    private const val END_MS = SAVE_AT_MS + WATCH_MS

    /** BLE 광고. 주소는 rotateMs마다 바뀌고(0이면 고정), 패킷 간격은 ±20% 흔들리며 loss 확률로 빠진다. */
    fun ble(
        prefix: String, from: Long, to: Long, rssi: Int, payload: ByteArray, name: String? = null, nameShare: Double = 1.0,
        intervalMs: Long = 1_000, loss: Double = 0.1, rotateMs: Long = 0,
    ): (Random) -> List<RfObservation> = { random ->
        val hints = bleHints(payload)
        val ids = manufacturerIds(payload)
        val phase = if (rotateMs > 0) random.nextLong(rotateMs) else 0L
        val out = mutableListOf<RfObservation>()
        var t = from + random.nextLong(intervalMs)
        while (t < to) {
            if (random.nextDouble() >= loss) {
                val epoch = if (rotateMs > 0) (t + phase) / rotateMs else 0
                val address = "%s:%02X:%02X".format(prefix, (epoch shr 8) and 0xFF, epoch and 0xFF)
                val shownName = name?.takeIf { random.nextDouble() < nameShare }
                out += RfObservation(address, ScanSource.BLE, shownName, rssi + random.nextInt(9) - 4, null, false, t, ids, hints)
            }
            t += (intervalMs * (0.8 + 0.4 * random.nextDouble())).toLong()
        }
        out
    }

    /** 와이파이 접속점. 30초 탐색마다 seen 확률로 결과에 들어간다(측정 중과 감시 중 확률을 따로 둔다). */
    fun wifi(bssid: String, ssid: String?, rssi: Int, seenCollect: Double, seenWatch: Double, from: Long = 0): (Random) -> List<RfObservation> = { random ->
        val out = mutableListOf<RfObservation>()
        var t = 2_000L + random.nextLong(5_000)
        while (t < END_MS) {
            val p = if (t <= SAVE_AT_MS) seenCollect else seenWatch
            if (t >= from && random.nextDouble() < p) {
                out += RfObservation(bssid, ScanSource.WIFI, ssid, rssi + random.nextInt(7) - 3, null, ssid == null, t, hints = wifiHints(ssid))
            }
            t += 30_000L
        }
        out
    }

    /** AD 구조에서 제조사 데이터의 회사 코드만 읽는다(Android ScanRecord.getManufacturerSpecificData와 같은 값). */
    fun manufacturerIds(payload: ByteArray): Set<Int> {
        val ids = mutableSetOf<Int>()
        var i = 0
        while (i < payload.size) {
            val length = payload[i].toInt() and 0xFF
            if (length == 0 || i + length >= payload.size) break
            if ((payload[i + 1].toInt() and 0xFF) == 0xFF && length >= 3) ids += (payload[i + 2].toInt() and 0xFF) or ((payload[i + 3].toInt() and 0xFF) shl 8)
            i += length + 1
        }
        return ids
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ad(type: Int, vararg data: Int) = bytes(data.size + 1, type, *data)

    val FLAGS = ad(0x01, 0x1A)
    /** Apple Nearby Info(0x10). 켜져 있는 아이폰·워치가 낸다. */
    val APPLE_NEARBY = FLAGS + ad(0xFF, 0x4C, 0x00, 0x10, 0x05, 0x01, 0x18, 0x2C, 0x9A, 0x4E)
    /** Apple Proximity Pairing(0x07, 길이 0x19). 케이스를 연 AirPods가 낸다. */
    val AIRPODS = ad(0xFF, 0x4C, 0x00, 0x07, 0x19, *IntArray(25) { 0x10 + it })
    /** 나의 찾기 오프라인 찾기 광고. OpenHaystack 펌웨어 템플릿과 같은 배치: 4C 00 12 19, 상태, 공개키 22바이트, 키 비트, 힌트. */
    val FIND_MY = ad(0xFF, 0x4C, 0x00, 0x12, 0x19, 0x00, *IntArray(22) { 0xA0 + it }, 0x02, 0x00)
    /** Espressif(0x02E5) 회사 코드를 쓰는 개발용 보드. */
    val ESPRESSIF = FLAGS + ad(0xFF, 0xE5, 0x02, 0x01, 0x02)
    val ESPRESSIF_ID = ad(0xFF, 0xE5, 0x02, 0x01, 0x02)
    val SAMSUNG_BUDS = FLAGS + ad(0xFF, 0x75, 0x00, 0x42, 0x09, 0x81)

    private const val WATCH_FROM = SAVE_AT_MS + 30_000

    /**
     * 모든 시나리오에 깔리는 교실 환경: 학교 공유기 3대(대역·SSID별 BSSID, 끝 자리만 다름), 숨긴 관리용 SSID,
     * 다른 층의 같은 이름 공유기, 감독관 휴대폰·시계·AirPods, 교탁 프로젝터, 시험 시작 후 제출된 꺼진 아이폰 8대.
     */
    fun background(): List<Emitter> = buildList {
        (0..2).forEach { ap ->
            val base = "00:1A:1E:4C:3$ap"
            val near = listOf(-55, -68, -80)[ap]
            add(Emitter("ap$ap 2.4G SCHOOL", Truth.FAMILIAR, wifi("$base:00", "SCHOOL", near, 0.95, 0.95)))
            add(Emitter("ap$ap 2.4G SCHOOL-GUEST", Truth.FAMILIAR, wifi("$base:01", "SCHOOL-GUEST", near, 0.95, 0.95)))
            add(Emitter("ap$ap 2.4G hidden", Truth.FAMILIAR, wifi("$base:02", null, near, 0.9, 0.9)))
            // 5GHz는 더 약하고, 먼 공유기의 5GHz는 측정 때 자주 빠진다.
            val seen = if (ap == 2) 0.15 else 0.8
            add(Emitter("ap$ap 5G SCHOOL", Truth.FAMILIAR, wifi("$base:10", "SCHOOL", near - 8, seen, seen + 0.1)))
            add(Emitter("ap$ap 5G SCHOOL-GUEST", Truth.FAMILIAR, wifi("$base:11", "SCHOOL-GUEST", near - 8, seen, seen + 0.1)))
        }
        add(Emitter("other floor SCHOOL (다른 회사)", Truth.FAMILIAR, wifi("A4:2B:B0:11:22:33", "SCHOOL", -84, 0.3, 0.4)))
        add(Emitter("proctor phone", Truth.FAMILIAR, ble("4A:10:00:00", 0, END_MS, -55, APPLE_NEARBY, rotateMs = 900_000)))
        add(Emitter("proctor watch", Truth.FAMILIAR, ble("4A:20:00:00", 0, END_MS, -60, APPLE_NEARBY, rotateMs = 840_000)))
        add(Emitter("proctor AirPods", Truth.FAMILIAR, ble("4A:30:00:00", 0, END_MS, -62, AIRPODS, "AirPods Pro", nameShare = 0.6, rotateMs = 900_000)))
        add(Emitter("projector", Truth.FAMILIAR, ble("C0:40:00:00", 0, END_MS, -70, FLAGS, "EPSON-PJ", intervalMs = 2_000)))
        (0..7).forEach { phone ->
            add(Emitter("submitted iPhone $phone", Truth.SUBMITTED_OFF, ble("5B:5$phone:00:00", WATCH_FROM, END_MS, -70 - phone, FIND_MY, intervalMs = 2_000, rotateMs = 900_000)))
        }
    }

    private fun target(label: String, truth: Truth, family: String, observations: (Random) -> List<RfObservation>) =
        Scenario(family, label, Emitter(label, truth, observations))

    /** 시나리오 목록. 각 시나리오는 배경 + 대상 송신기 하나다. */
    val scenarios: List<Scenario> = listOf(
        Scenario("대조", "배경만", null),
        // 빈 교실 때부터 있던 기기: 감시 목록에 뜨면 오탐
        target("측정 때 놓친 공유기 대역(같은 SSID)", Truth.FAMILIAR, "기존 기반 시설", wifi("00:1A:1E:4C:31:14", "SCHOOL", -74, 0.0, 0.9)),
        target("측정 때 놓친 숨긴 관리용 SSID", Truth.FAMILIAR, "기존 기반 시설", wifi("00:1A:1E:4C:31:12", null, -74, 0.0, 0.9)),
        target("측정 때 놓친 행사용 SSID, 중간 세기", Truth.FAMILIAR, "기존 기반 시설", wifi("00:1A:1E:4C:32:13", "SCHOOL-EVENT", -78, 0.0, 0.9)),
        target("측정 때 놓친 행사용 SSID, 약함", Truth.FAMILIAR, "기존 기반 시설", wifi("00:1A:1E:4C:32:13", "SCHOOL-EVENT", -88, 0.0, 0.9)),
        target("감독관 이어폰 이름 없이 주소 교대", Truth.FAMILIAR, "주소 교대", ble("4A:31:00:00", 0, END_MS, -60, AIRPODS, "AirPods Pro", nameShare = 0.3, rotateMs = 600_000)),
        // 교실 밖
        target("옆 교실 핫스팟, 약함", Truth.OUTSIDE_ROOM, "교실 밖", wifi("DE:11:22:33:44:55", "Galaxy S24", -88, 0.0, 0.9, WATCH_FROM)),
        target("옆 교실 핫스팟, 중간", Truth.OUTSIDE_ROOM, "교실 밖", wifi("DE:11:22:33:44:56", "Galaxy S24", -76, 0.0, 0.9, WATCH_FROM)),
        // 교실 안에 새로 들어온 기기
        target("이름 있는 무선 이어폰", Truth.NEW_IN_ROOM, "새 BLE", ble("6C:10:00:00", WATCH_FROM, END_MS, -60, SAMSUNG_BUDS, "Galaxy Buds3 Pro")),
        target("이름 없는 Apple 휴대폰(같은 종류가 빈 교실에 2대)", Truth.NEW_IN_ROOM, "새 BLE", ble("6C:20:00:00", WATCH_FROM, END_MS, -62, APPLE_NEARBY, rotateMs = 900_000)),
        target("이름 없는 Apple 휴대폰, 20초 간격", Truth.NEW_IN_ROOM, "새 BLE", ble("6C:21:00:00", WATCH_FROM, END_MS, -62, APPLE_NEARBY, intervalMs = 20_000, loss = 0.0)),
        target("개발용 보드(Espressif, 이름 없음)", Truth.NEW_IN_ROOM, "새 BLE", ble("7E:10:00:00", WATCH_FROM, END_MS, -58, ESPRESSIF)),
        target("기본 이름 핫스팟", Truth.NEW_IN_ROOM, "새 와이파이", wifi("DA:A1:B2:C3:D4:E5", "iPhone", -55, 0.0, 0.95, WATCH_FROM)),
        target("학교 SSID 복사, 임의 주소", Truth.NEW_IN_ROOM, "이름 복사", wifi("DA:01:02:03:04:05", "SCHOOL", -55, 0.0, 0.95, WATCH_FROM)),
        target("학교 SSID 복사, 다른 회사 고유 주소", Truth.NEW_IN_ROOM, "이름 복사", wifi("24:0A:C4:12:34:56", "SCHOOL", -55, 0.0, 0.95, WATCH_FROM)),
        target("학교 SSID 대소문자만 바꿈", Truth.NEW_IN_ROOM, "이름 복사", wifi("24:0A:C4:12:34:57", "school", -55, 0.0, 0.95, WATCH_FROM)),
        target("공유기 끝 자리 옆 BSSID, 새 SSID", Truth.NEW_IN_ROOM, "주소 흉내", wifi("00:1A:1E:4C:30:05", "CAM-7F21", -55, 0.0, 0.95, WATCH_FROM)),
        target("공유기 끝 자리 옆 BSSID, 숨긴 SSID", Truth.NEW_IN_ROOM, "주소 흉내", wifi("00:1A:1E:4C:30:06", null, -55, 0.0, 0.95, WATCH_FROM)),
        target("나의 찾기 형식 + 기기 이름", Truth.NEW_IN_ROOM, "나의 찾기 흉내", ble("7E:20:00:00", WATCH_FROM, END_MS, -58, FIND_MY, "ESP32-BLE")),
        target("나의 찾기 형식 + 다른 회사 코드", Truth.NEW_IN_ROOM, "나의 찾기 흉내", ble("7E:30:00:00", WATCH_FROM, END_MS, -58, FIND_MY + ESPRESSIF_ID)),
        target("나의 찾기 형식 그대로", Truth.NEW_IN_ROOM, "나의 찾기 흉내", ble("7E:40:00:00", WATCH_FROM, END_MS, -58, FIND_MY)),
    )

    val SEEDS: List<Long> = (1L..20L).toList()

    fun runAll(): List<RunResult> = scenarios.flatMap { scenario -> SEEDS.map { run(scenario, it) } }

    fun report(results: List<RunResult>): String = buildString {
        appendLine("| 분류 | 시나리오 | 정답 | 새 주소 | 새 묶음 | 목록에 뜬 실행 | 30초 이상 | 틀린 실행 | 첫 표시(중앙값, 초) |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        results.filter { it.target != null }.groupBy { it.scenario }.forEach { (scenario, runs) ->
            val targets = runs.mapNotNull { it.target }
            val firsts = targets.mapNotNull { it.firstListedMs }.sorted()
            val median = firsts.getOrNull(firsts.size / 2)?.let { "%.0f".format(it / 1000.0) } ?: "-"
            appendLine("| ${scenario.family} | ${scenario.label} | ${targets.first().truth} | ${targets.count { it.newObservation }}/${targets.size} | " +
                "${targets.count { it.newGroup }}/${targets.size} | ${targets.count { it.listed }}/${targets.size} | ${targets.count { it.sustained }}/${targets.size} | ${targets.count { it.wrong }}/${targets.size} | $median |")
        }
        val background = results.flatMap { it.background }
        appendLine()
        appendLine("배경 송신기(실행 ${results.size}회 × ${background.size / results.size}개): 목록에 뜬 FAMILIAR ${background.count { it.truth == Truth.FAMILIAR && it.listed }}, " +
            "목록에 뜬 SUBMITTED_OFF ${background.count { it.truth == Truth.SUBMITTED_OFF && it.listed }}")
        background.filter { it.wrong }.groupBy { it.label }.forEach { (label, rows) ->
            appendLine("- 배경 오탐: $label ${rows.size}회 (30초 이상 ${rows.count { it.sustained }}회, 목록에 있던 시간 합계 ${rows.sumOf { it.listedTicks } * TICK_MS / 1000}초)")
        }
    }
}
