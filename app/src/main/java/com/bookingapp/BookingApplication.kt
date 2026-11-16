package com.bookingapp

import android.app.Application

class BookingApplication : Application() {

    private var appContainer: AppContainer? = null

    /** Created on first use; tests may replace it before launching an activity. */
    var container: AppContainer
        @Synchronized get() = appContainer ?: AppContainer(this).also { appContainer = it }
        @Synchronized set(value) {
            appContainer = value
        }
}
