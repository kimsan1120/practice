package com.kimsan.runexport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Health Connect 권한 화면에서 "이 앱이 데이터를 어떻게 쓰나요?" 를 누르면 보이는 화면 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.systemBarsPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("데이터 사용 안내", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "이 앱은 Health Connect 에 저장된 운동 기록(운동 세션, 거리, 심박수, 속도, 칼로리, 걸음 수, " +
                                "상승 고도, GPS 경로)을 읽어서 화면에 보여주고, 사용자가 '공유' 또는 '저장'을 누를 때만 " +
                                "CSV/GPX 파일로 만들어요.\n\n" +
                                "· 데이터를 쓰거나 수정하지 않아요.\n" +
                                "· 인터넷으로 어디에도 보내지 않아요.\n" +
                                "· 권한은 Health Connect 설정에서 언제든 끌 수 있어요."
                        )
                        Button(onClick = { finish() }) { Text("확인") }
                    }
                }
            }
        }
    }
}
