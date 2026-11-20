package com.classroom.rfdetector.core

import com.classroom.rfdetector.core.GroupingEvaluation.Truth
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GroupingEvaluation의 전체 시나리오(시나리오 21개 × 시드 20개)를 돌려 build/reports/grouping-eval/summary.md에 결과표를 쓴다.
 * 놓침이 알려진 시나리오는 표에만 남기고, 아래 불변 조건만 검사한다.
 */
class GroupingEvaluationTest {
    @Test fun scenarioReport() {
        val results = GroupingEvaluation.runAll()
        File("build/reports/grouping-eval").apply { mkdirs() }.resolve("summary.md").writeText(GroupingEvaluation.report(results))

        val familiar = results.flatMap { run -> run.background + listOfNotNull(run.target) }.filter { it.truth == Truth.FAMILIAR }
        assertTrue("원래 있던 기기가 30초 넘게 새 전파로 떴음: ${familiar.filter { it.sustained }.map { it.label }.toSet()}", familiar.none { it.sustained })
        val submitted = results.flatMap { it.background }.filter { it.truth == Truth.SUBMITTED_OFF }
        assertEquals(results.size * 8, submitted.size)
        assertTrue("꺼서 제출한 아이폰이 새 전파로 떴음", submitted.none { it.listed })

        val findMyImitations = results.filter { it.scenario.family == "나의 찾기 흉내" && it.scenario.label != "나의 찾기 형식 그대로" }.mapNotNull { it.target }
        assertEquals(40, findMyImitations.size)
        assertTrue(findMyImitations.all { it.listed })
    }
}
