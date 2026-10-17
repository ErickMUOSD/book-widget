package com.bookwidget.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.bookwidget.app.ui.App
import com.bookwidget.app.ui.AppViewModel
import com.bookwidget.app.ui.Retro

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Retro.Accent,
                    secondary = Retro.Accent2,
                    background = Retro.Bg,
                    surface = Retro.Panel,
                    error = Retro.Danger,
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
