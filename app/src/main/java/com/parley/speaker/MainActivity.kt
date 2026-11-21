package com.parley.speaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.parley.speaker.ui.ParleyRoot
import com.parley.speaker.ui.theme.ParleyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ParleyTheme {
                ParleyRoot()
            }
        }
    }
}
