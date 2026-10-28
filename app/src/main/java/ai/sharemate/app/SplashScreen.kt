package ai.sharemate.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Used only while the stored logbook is loading; no artificial splash timer. */
@Composable
internal fun SharemateSplash(modifier: Modifier = Modifier) {
    SharemateTheme(dark = true) {
        BoxWithConstraints(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF252527), Color(0xFF17181B))))
            .safeDrawingPadding().padding(28.dp), contentAlignment = Alignment.Center) {
            val narrow = maxWidth < 480.dp
            Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Icon(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(if (narrow) 88.dp else 112.dp), tint = Color(0xFFE4BFA6))
                Text("SHAREMATE", style = (if (narrow) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge)
                    .copy(letterSpacing = 4.sp), color = Color.White, maxLines = 1)
                Text("이동은 자연스럽게, 기록은 정확하게", style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFC7C9CE), textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.width(200.dp).height(2.dp), color = Color(0xFFE4BFA6), trackColor = Color(0xFF44464E))
                Text("운행일지를 불러오고 있어요", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC7C9CE))
            }
        }
    }
}
