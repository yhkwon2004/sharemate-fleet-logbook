package ai.sharemate.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * Loading on the phone looks like the screen that is about to appear: the greeting lines, the 2 × 2 destination cards and
 * one widget row, in the surface colour, pulsing slowly (still under reduced motion). It is drawn only while the stored
 * copy is being read; there is no timer and nothing pretends to progress.
 */

/**
 * A home-shaped placeholder shown instead of the brand splash while the copy loads: greeting, four cards, two widgets. It is
 * drawn before the shell exists, so it measures the window itself (ProvideLayout) to use the phone's gutters and content width.
 */
@Composable internal fun PhoneHomeSkeleton(modifier: Modifier = Modifier) {
    ProvideLayout(compactApp = true, modifier.fillMaxWidth()) { layout ->
        Column(Modifier.contentWidth().align(Alignment.TopCenter).padding(horizontal = layout.gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(layout.cardGap)) {
            Column(Modifier.fillMaxWidth().padding(top = 60.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlock(height = 12.dp, width = 140.dp)
                SkeletonBlock(height = 28.dp, width = 220.dp, corner = 10.dp)
                SkeletonBlock(height = 14.dp, width = 180.dp)
            }
            repeat(2) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.cardGap)) {
                    repeat(2) { PhoneCardSkeleton(Modifier.weight(1f)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.cardGap)) {
                repeat(2) { PhoneCardSkeleton(Modifier.weight(1f), height = 120.dp) }
            }
            Text("운행일지를 불러오고 있어요", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** One destination-card placeholder: a tile and two lines. */
@Composable private fun PhoneCardSkeleton(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 84.dp) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().height(height).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBlock(height = 44.dp, width = 44.dp, corner = 13.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { SkeletonBlock(height = 14.dp); SkeletonBlock(height = 12.dp, width = 72.dp) }
        }
    }
}

/** One 88dp list row placeholder, the shape of a trip row: time, two text lines, a chip. */
@Composable internal fun PhoneRowSkeleton(modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SkeletonBlock(height = 16.dp, width = 44.dp); SkeletonBlock(height = 12.dp, width = 36.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { SkeletonBlock(height = 16.dp); SkeletonBlock(height = 12.dp, width = 160.dp) }
            Spacer(Modifier.width(4.dp))
            SkeletonBlock(height = 28.dp, width = 56.dp, corner = 12.dp)
        }
    }
}

/** A list-shaped placeholder for the trips tab: a month header line and five rows. */
@Composable internal fun PhoneListSkeleton(modifier: Modifier = Modifier, rows: Int = 5) {
    Column(modifier.fillMaxWidth().padding(horizontal = LocalLayout.current.gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBlock(height = 18.dp, width = 200.dp, corner = 6.dp)
        repeat(rows) { PhoneRowSkeleton() }
    }
}
