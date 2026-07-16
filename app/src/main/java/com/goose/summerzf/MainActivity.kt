package com.goose.summerzf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import com.goose.summerzf.navigation.AppNavGraph
import com.goose.summerzf.ui.theme.SummerZephyrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SummerZephyrTheme {
                SummerZephyrApp()
            }
        }
    }
}

@Composable
fun SummerZephyrApp() {
    SummerZephyrTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            val navController = rememberNavController()
            AppNavGraph(navController = navController)
        }
    }
}