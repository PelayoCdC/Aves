package dev.pelayo.aves

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.pelayo.aves.core.designsystem.theme.AvesTheme
import dev.pelayo.aves.ui.AvesApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AvesApplication).container
        setContent {
            AvesTheme {
                AvesApp(container)
            }
        }
    }
}
