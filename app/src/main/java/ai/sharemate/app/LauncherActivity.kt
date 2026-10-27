package ai.sharemate.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val automotive = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        // Keep android.car classes out of the phone launch path.
        val target = if (automotive) "ai.sharemate.app.MainActivity" else "ai.sharemate.app.PhoneActivity"
        // Reuse a running screen: two instances would each own a ViewModel writing the same session file.
        startActivity(Intent().setClassName(this, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }
}
