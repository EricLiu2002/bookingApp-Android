package com.bookingapp.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bookingapp.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer provided") }

/**
 * Creates a ViewModel from the current [AppContainer]. Repositories are taken from the
 * container at creation time, so a ViewModel created after a ledger reload uses the new file.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer, SavedStateHandle) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(factory = viewModelFactory { initializer { create(container, createSavedStateHandle()) } })
}
