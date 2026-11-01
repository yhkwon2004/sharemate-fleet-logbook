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
 * Loading on the phone looks like the screen that is about to appear: a hero card and a few rows, in the surface
 * colour, pulsing slowly (still under reduced motion). It is drawn only while the stored copy is being read; there
 * is no timer and nothing pretends to progress.
 */

/** A home-shaped placeholder shown instead of the brand splash while the copy loads: hero card + three task rows. */
@Composable internal fun PhoneHomeSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SkeletonBlock(height = 12.dp, width = 140.dp)
                SkeletonBlock(height = 40.dp, width = 180.dp, corner = 12.dp)
                SkeletonBlock(height = 14.dp, width = 220.dp)
            }
        }
        repeat(3) { PhoneRowSkeleton() }
        Text("운행일지를 불러오고 있어요", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
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
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonBlock(height = 18.dp, width = 200.dp, corner = 6.dp)
        repeat(rows) { PhoneRowSkeleton() }
    }
}
