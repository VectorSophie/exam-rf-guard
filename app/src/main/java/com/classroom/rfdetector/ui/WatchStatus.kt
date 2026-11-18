package com.classroom.rfdetector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** 무선 하나의 수신 상태 한 줄. problem이면 경고색으로 보이고, 새 전파가 없을 때 제목을 "완전하지 않음"으로 바꾼다. */
@Immutable
data class RadioLine(val label: String, val detail: String, val problem: Boolean = false)

@Immutable
data class WatchStatusState(
    val watching: Boolean,
    val newCount: Int,
    val elapsed: String,
    val radios: List<RadioLine>,
) {
    val incomplete: Boolean get() = watching && radios.any { it.problem }
}

/**
 * 감시 화면 맨 위의 상태. 새 전파가 있으면 개수가 먼저 보이고, 없을 때는 "이상 없음"처럼 읽히지 않게 색을 쓰지 않는다.
 * 무선마다 마지막으로 받은 시각을 보여 줘서 조용한 화면이 탐색이 멈춘 것인지 구분할 수 있게 한다.
 */
@Composable
fun WatchStatus(state: WatchStatusState, modifier: Modifier = Modifier) {
    val found = state.watching && state.newCount > 0
    val accent = when {
        found || state.incomplete -> RfColors.warning
        else -> RfColors.textPrimary
    }
    Surface(
        modifier = modifier,
        color = RfColors.surface,
        shape = RoundedCornerShape(12.dp),
        border = if (found) BorderStroke(1.dp, RfColors.warning) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        !state.watching -> "감시를 끝냈습니다"
                        found -> "새 전파 ${state.newCount}개 발견"
                        state.incomplete -> "감시가 완전하지 않습니다"
                        else -> "새 전파 없음"
                    },
                    Modifier.semantics { heading() },
                    color = if (state.watching) accent else RfColors.textPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(if (state.watching) "감시 중 · ${state.elapsed}" else "지금은 전파를 받지 않습니다", color = RfColors.textDim, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.watching && state.radios.isNotEmpty()) {
                HorizontalDivider(color = RfColors.surfaceAlt)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.radios.forEach { radio ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(radio.label, Modifier.width(64.dp), color = RfColors.textDim, style = MaterialTheme.typography.bodyMedium)
                            Text(radio.detail, Modifier.weight(1f), color = if (radio.problem) RfColors.warning else RfColors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Text(
                when {
                    found -> "빈 교실에 없던 전파입니다. 줄을 눌러 다가가며 세기를 확인하세요. 사라지면 목록에서 빠집니다."
                    state.incomplete -> "위 문제가 풀릴 때까지는 그 무선의 새 전파가 목록에 나오지 않습니다."
                    else -> "잡히지 않는다고 기기가 없는 것은 아닙니다. 블루투스·와이파이를 끈 기기와 LTE만 쓰는 기기는 보이지 않습니다."
                },
                color = RfColors.textDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val healthyRadios = listOf(
    RadioLine("와이파이", "12초 전 탐색 완료 · 24회"),
    RadioLine("블루투스", "1초 전 수신"),
)

@Preview(name = "감시 중 · 새 전파 없음", widthDp = 360, backgroundColor = 0xFF0A1118, showBackground = true)
@Composable
private fun WatchStatusClearPreview() = AppRfTheme {
    WatchStatus(WatchStatusState(watching = true, newCount = 0, elapsed = "12분 3초", radios = healthyRadios), Modifier.padding(16.dp))
}

@Preview(name = "감시 중 · 새 전파", widthDp = 360, backgroundColor = 0xFF0A1118, showBackground = true)
@Composable
private fun WatchStatusFoundPreview() = AppRfTheme {
    WatchStatus(WatchStatusState(watching = true, newCount = 2, elapsed = "41분 10초", radios = healthyRadios), Modifier.padding(16.dp))
}

@Preview(name = "감시 중 · 탐색 문제", widthDp = 360, backgroundColor = 0xFF0A1118, showBackground = true)
@Composable
private fun WatchStatusScannerProblemPreview() = AppRfTheme {
    WatchStatus(
        WatchStatusState(
            watching = true, newCount = 0, elapsed = "41분 10초",
            radios = listOf(
                RadioLine("와이파이", "3분 12초 전 탐색 완료 · 새 결과 없음", problem = true),
                RadioLine("블루투스", "탐색 오류 (2) · 잠시 후 다시 시작하세요", problem = true),
            ),
        ),
        Modifier.padding(16.dp),
    )
}

@Preview(name = "감시 끝냄", widthDp = 360, backgroundColor = 0xFF0A1118, showBackground = true)
@Composable
private fun WatchStatusEndedPreview() = AppRfTheme {
    WatchStatus(WatchStatusState(watching = false, newCount = 0, elapsed = "1시간 2분", radios = emptyList()), Modifier.padding(16.dp))
}
