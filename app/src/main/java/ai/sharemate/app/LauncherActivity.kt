package ai.sharemate.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Two APKs from one code base: the car build opens the car screens, the phone build the companion. Which one this is was
        // decided when it was built (BuildConfig.CAR), so the file a person installs is the app they get, whatever the hardware.
        // It also keeps android.car classes out of the phone launch path.
        val target = if (BuildConfig.CAR) "ai.sharemate.app.MainActivity" else "ai.sharemate.app.PhoneLogbookActivity"
        // Reuse a running screen: two instances would each own a ViewModel writing the same session file.
        startActivity(Intent().setClassName(this, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }
}
