package com.kimsan.runexport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 프로필: 심박 구간 / VO2max 등급 / 체중 계산에 쓰는 값 */
@Composable
fun SettingsScreen(profile: Profile, onSave: (Profile) -> Unit, onOpenHealthConnect: () -> Unit) {
    var birth by rememberSaveable { mutableStateOf(profile.birthYear?.toString() ?: "") }
    var sex by rememberSaveable { mutableStateOf(profile.sex?.name) }
    var maxHr by rememberSaveable { mutableStateOf(profile.maxHr?.toString() ?: "") }
    var aet by rememberSaveable { mutableStateOf(profile.aerobicThreshold?.toString() ?: "") }
    var ant by rememberSaveable { mutableStateOf(profile.anaerobicThreshold?.toString() ?: "") }
    var weight by rememberSaveable { mutableStateOf(profile.weightKg?.toString() ?: "") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("모든 값은 휴대폰 안에만 저장돼요. 비워두면 사용하지 않아요.", style = MaterialTheme.typography.bodySmall)

        NumberField("출생연도 (예: 1999)", birth, 4) { birth = it }
        Text("성별 (VO2max 등급용)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sex == Sex.MALE.name, onClick = { sex = if (sex == Sex.MALE.name) null else Sex.MALE.name }, label = { Text("남성") })
            FilterChip(selected = sex == Sex.FEMALE.name, onClick = { sex = if (sex == Sex.FEMALE.name) null else Sex.FEMALE.name }, label = { Text("여성") })
        }

        NumberField("최대 심박 (비우면 220 − 나이)", maxHr, 3) { maxHr = it }
        Text(
            "심박 5구간은 삼성 헬스와 같은 방식: 구간 1 = 50–60%, 2 = 60–70%, 3 = 70–80%, 4 = 80–90%, 5 = 90% 이상. " +
                "삼성 헬스 '심박수 구간' 화면의 최대 심박(예: 186)을 넣으면 경계가 똑같아져요.",
            style = MaterialTheme.typography.bodySmall,
        )

        NumberField("유산소 역치 심박 (예: 131)", aet, 3) { aet = it }
        NumberField("무산소 역치 심박 (예: 173)", ant, 3) { ant = it }
        Text("삼성 헬스 '심박수 구간' 화면에 나오는 역치 값을 그대로 넣으면 같은 기준으로 3구간을 계산해요.", style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = weight,
            onValueChange = { v -> weight = v.filter { it.isDigit() || it == '.' }.take(5) },
            label = { Text("체중 kg (Health Connect 에 없을 때 사용)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                onSave(
                    Profile(
                        birthYear = birth.toIntOrNull()?.takeIf { it in 1900..2030 },
                        sex = sex?.let { Sex.valueOf(it) },
                        maxHr = maxHr.toIntOrNull()?.takeIf { it in 100..240 },
                        aerobicThreshold = aet.toIntOrNull()?.takeIf { it in 60..230 },
                        anaerobicThreshold = ant.toIntOrNull()?.takeIf { it in 60..240 },
                        weightKg = weight.toDoubleOrNull()?.takeIf { it in 20.0..300.0 },
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("저장") }

        Text("Health Connect 권한", style = MaterialTheme.typography.labelLarge)
        Text(
            "GPS 경로를 기록마다 묻지 않게 하려면 Health Connect 설정 → 앱 권한 → 러닝 기록 내보내기 → '운동 경로' 를 '항상 허용' 으로 바꾸세요.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onOpenHealthConnect, modifier = Modifier.fillMaxWidth()) { Text("Health Connect 설정 열기") }
    }
}

@Composable
private fun NumberField(label: String, value: String, maxLen: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(maxLen)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
