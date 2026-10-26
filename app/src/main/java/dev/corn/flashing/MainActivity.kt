package dev.corn.flashing

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.corn.flashing.ui.CornApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App is always dark charcoal. Force light (white) status/nav icons so they stay
        // readable when the phone system theme is light (default enableEdgeToEdge follows system).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        applyLightSystemBarIcons()
        setContent { CornApp(viewModel()) }
    }

    override fun onResume() {
        super.onResume()
        applyLightSystemBarIcons()
    }

    /** Keep status/nav icons light (white) on our dark charcoal UI, even if the system theme is light. */
    private fun applyLightSystemBarIcons() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
