package com.solplay.iptv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        TrialManager.ensureFirstLaunchRecorded(this)

        Handler(Looper.getMainLooper()).postDelayed({
            val next = if (TrialManager.canAccessApp(this)) {
                PlaylistActivity::class.java
            } else {
                LicenseActivity::class.java
            }
            startActivity(Intent(this, next))
            finish()
        }, 1500)
    }
}
