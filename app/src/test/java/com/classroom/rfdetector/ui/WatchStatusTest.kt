package com.classroom.rfdetector.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchStatusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val failedBle = listOf(RadioLine("와이파이", "12초 전 탐색 완료 · 24회"), RadioLine("블루투스", "BLE 탐색 오류 (2)", problem = true))

    @Test fun newSignalsComeFirstEvenWhileAScannerHasAProblem() {
        compose.setContent { AppRfTheme { WatchStatus(WatchStatusState(watching = true, newCount = 2, elapsed = "1분", radios = failedBle)) } }
        compose.onNodeWithText("새 전파 2개 발견").assertIsDisplayed()
        compose.onNodeWithText("BLE 탐색 오류 (2)").assertIsDisplayed()
    }

    @Test fun aScannerProblemIsNotShownAsNoNewSignals() {
        compose.setContent { AppRfTheme { WatchStatus(WatchStatusState(watching = true, newCount = 0, elapsed = "1분", radios = failedBle)) } }
        compose.onNodeWithText("감시가 완전하지 않습니다").assertIsDisplayed()
        compose.onNodeWithText("새 전파 없음").assertDoesNotExist()
    }
}
