package com.jarvis.ai.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import com.jarvis.ai.R
import com.jarvis.ai.ui.main.MainActivity
import java.util.Locale

class SplashActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        tts = TextToSpeech(this, this)

        val tvJarvis = findViewById<TextView>(R.id.tvSplashTitle)
        val tvModby = findViewById<TextView>(R.id.tvSplashModby)
        val tvStatus = findViewById<TextView>(R.id.tvSplashStatus)
        val progressBar = findViewById<ProgressBar>(R.id.splashProgress)
        val ivCircle = findViewById<ImageView>(R.id.ivSplashCircle)

        val tvWelcome = findViewById<TextView>(R.id.tvWelcome)

        // Initially invisible
        tvJarvis.alpha = 0f
        tvModby.alpha = 0f
        tvWelcome.alpha = 0f
        tvStatus.alpha = 0f
        progressBar.alpha = 0f
        ivCircle.alpha = 0f
        ivCircle.scaleX = 0.3f
        ivCircle.scaleY = 0.3f

        // Step 1: Arc reactor circle appears with scale animation (0ms)
        val circleAppear = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(ivCircle, "alpha", 0f, 1f).setDuration(600),
                ObjectAnimator.ofFloat(ivCircle, "scaleX", 0.3f, 1.2f, 1f).setDuration(800),
                ObjectAnimator.ofFloat(ivCircle, "scaleY", 0.3f, 1.2f, 1f).setDuration(800)
            )
            interpolator = OvershootInterpolator(1.5f)
        }

        // Step 2: Circle rotation (continuous)
        val circleRotate = ObjectAnimator.ofFloat(ivCircle, "rotation", 0f, 360f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        // Step 3: Title "JARVIS AI" fades in (400ms)
        val titleFade = ObjectAnimator.ofFloat(tvJarvis, "alpha", 0f, 1f).setDuration(500)

        // Step 4: "modby piash" fades in (700ms)
        val modbyFade = ObjectAnimator.ofFloat(tvModby, "alpha", 0f, 1f).setDuration(400)

        // Step 5: Status text + progress (900ms)
        val statusFade = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(tvStatus, "alpha", 0f, 1f).setDuration(300),
                ObjectAnimator.ofFloat(progressBar, "alpha", 0f, 1f).setDuration(300)
            )
        }

        // Play sequence
        circleAppear.start()
        circleRotate.start()

        Handler(Looper.getMainLooper()).postDelayed({ titleFade.start() }, 400)
        Handler(Looper.getMainLooper()).postDelayed({ modbyFade.start() }, 700)
        Handler(Looper.getMainLooper()).postDelayed({ statusFade.start() }, 900)

        Handler(Looper.getMainLooper()).postDelayed({
            ObjectAnimator.ofFloat(tvWelcome, "alpha", 0f, 1f).setDuration(500).start()
        }, 1200)

        // Animate status text
        val statuses = listOf(
            "Initializing systems...",
            "Loading AI core...",
            "Connecting neural network...",
            "Voice engine ready...",
            "All systems online."
        )
        statuses.forEachIndexed { index, status ->
            Handler(Looper.getMainLooper()).postDelayed({
                tvStatus.text = status
            }, 1000L + (index * 400L))
        }

        // Navigate to MainActivity after 3 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 3200)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("bn", "BD")
            tts?.speak("Welcome Boss! Jarvis systems online.", TextToSpeech.QUEUE_FLUSH, null, "welcome")
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
