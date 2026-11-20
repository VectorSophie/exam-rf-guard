package com.classroom.rfdetector.core

import java.io.File
import org.junit.Test

class GroupingEvaluationTest {
    @Test fun writeReport() {
        val results = GroupingEvaluation.runAll()
        val report = GroupingEvaluation.report(results)
        File("build/reports/grouping-eval").apply { mkdirs() }.resolve("summary.md").writeText(report)
        println(report)
    }
}
