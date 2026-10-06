package com.bookwidget.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.bookwidget.app.ui.App
import com.bookwidget.app.ui.AppViewModel

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8B7BFF),
                    background = Color(0xFF0F1117),
                    surface = Color(0xFF181B24),
                ),
            ) {
                App(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.refreshBooks() // al volver a la app, sincroniza con lo hecho desde el widget o la web
    }
}
