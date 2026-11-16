package com.bookingapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.bookingapp.ui.common.LocalAppContainer
import com.bookingapp.ui.common.LocalCurrency
import com.bookingapp.ui.navigation.BookingNavHost
import com.bookingapp.ui.theme.BookingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BookingApplication).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalCurrency provides container.currency) {
                BookingTheme { BookingNavHost() }
            }
        }
    }
}
