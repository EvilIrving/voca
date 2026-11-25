package com.voca.speaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.voca.speaker.ui.VocaRoot
import com.voca.speaker.ui.theme.VocaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VocaTheme {
                VocaRoot()
            }
        }
    }
}
