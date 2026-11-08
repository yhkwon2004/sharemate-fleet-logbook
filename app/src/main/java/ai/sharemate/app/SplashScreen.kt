package ai.sharemate.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Car screen only, shown while the stored logbook is loading; no artificial splash timer. The phone draws
 * [PhoneHomeSkeleton] instead, the shape of the screen that is about to appear. 2.10: the splash sits on the scene's own
 * backdrop (the shell paints it), so the first frame is already in the light the home will use; under reduced motion the
 * activity line stands still.
 */
@Composable
internal fun SharemateSplash(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), contentAlignment = Alignment.Center) {
        val narrow = maxWidth < 480.dp
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(if (narrow) 88.dp else 112.dp), tint = Scene.brand)
            Text("SHAREMATE", style = (if (narrow) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge)
                .copy(letterSpacing = 4.sp), color = Scene.text, maxLines = 1)
            Text("이동은 자연스럽게, 기록은 정확하게", style = MaterialTheme.typography.bodyMedium,
                color = Scene.textSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            if (LocalReduceMotion.current) Box(Modifier.width(200.dp).height(2.dp).background(Scene.ringTrack, RoundedCornerShape(1.dp)))
            else LinearProgressIndicator(Modifier.width(200.dp).height(2.dp), color = Scene.brand, trackColor = Scene.ringTrack)
            Text("운행일지를 불러오고 있어요", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
        }
    }
}
