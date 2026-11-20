package com.bookingapp.ui.common

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.YearMonth

/**
 * A month kept in [SavedStateHandle] as `YYYY-MM` text, so it survives rotation and process
 * death. Defaults to the current month in the phone's time zone; a route argument with the
 * same key pre-selects it.
 */
fun SavedStateHandle.savedMonth(key: String, clock: Clock, scope: CoroutineScope): StateFlow<YearMonth> {
    val initial = get<String>(key)?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.now(clock)
    set(key, initial.toString())
    return getStateFlow(key, initial.toString()).map(YearMonth::parse).stateIn(scope, SharingStarted.Eagerly, initial)
}
