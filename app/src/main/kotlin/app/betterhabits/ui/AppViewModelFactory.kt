package app.betterhabits.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.betterhabits.BetterHabitsApplication
import app.betterhabits.MainViewModel
import app.betterhabits.di.AppContainer
import app.betterhabits.ui.profile.ProfileViewModel

/** Builds every ViewModel from the [AppContainer]; the single place ViewModels are wired. */
object AppViewModelFactory {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { MainViewModel(container().userPreferencesRepository) }
        initializer { ProfileViewModel(container().userPreferencesRepository) }
    }

    private fun CreationExtras.container(): AppContainer =
        (this[APPLICATION_KEY] as BetterHabitsApplication).container
}
