package com.jarvis.ai.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.ai.R

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val ivReactor = findViewById<ImageView>(R.id.ivAboutReactor)
        val tvVersion = findViewById<TextView>(R.id.tvAboutVersion)
        val btnFacebook = findViewById<TextView>(R.id.btnFacebook)
        val btnBack = findViewById<TextView>(R.id.btnAboutBack)

        // Rotating reactor animation
        ObjectAnimator.ofFloat(ivReactor, "rotation", 0f, 360f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Pulse alpha on version
        ObjectAnimator.ofFloat(tvVersion, "alpha", 0.5f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        // Version info
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "v${pInfo.versionName} (build ${pInfo.versionCode})"
        } catch (_: Exception) {
            tvVersion.text = "v5.0.0"
        }

        btnFacebook.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://fb.com/piashmsuf")))
            } catch (_: Exception) {}
        }

        btnBack.setOnClickListener { finish() }
    }
}
