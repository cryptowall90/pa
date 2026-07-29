package com.pictureperfectx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.pictureperfectx.app.ui.PicturePerfectRoot
import com.pictureperfectx.app.ui.theme.PicturePerfectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PicturePerfectTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    PicturePerfectRoot()
                }
            }
        }
    }
}
