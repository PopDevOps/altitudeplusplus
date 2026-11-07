package com.altitudeplusplus.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altitudeplusplus.android.ui.AltitudeTheme
import com.altitudeplusplus.android.ui.AppViewModel
import com.altitudeplusplus.android.ui.HomeScreen
import com.altitudeplusplus.android.ui.SignInScreen

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AltitudeTheme {
                val session by model.session.collectAsStateWithLifecycle()
                if (session == null) SignInScreen(model) else HomeScreen(model)
            }
        }
    }
}
