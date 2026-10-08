package app.betterhabits.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.betterhabits.BetterHabitsApplication
import app.betterhabits.MainViewModel
import app.betterhabits.di.AppContainer
import app.betterhabits.ui.auth.ChildSignInViewModel
import app.betterhabits.ui.auth.ForgotPasswordViewModel
import app.betterhabits.ui.auth.SignInViewModel
import app.betterhabits.ui.auth.SignUpViewModel
import app.betterhabits.ui.auth.VerifyEmailViewModel
import app.betterhabits.ui.chores.ChoreDetailViewModel
import app.betterhabits.ui.chores.ChoreEditorViewModel
import app.betterhabits.ui.chores.ChoresViewModel
import app.betterhabits.ui.chores.HistoryViewModel
import app.betterhabits.ui.household.HouseholdSettingsViewModel
import app.betterhabits.ui.household.HouseholdSetupViewModel
import app.betterhabits.ui.household.HouseholdViewModel
import app.betterhabits.ui.household.MemberDetailViewModel
import app.betterhabits.ui.profile.ProfileViewModel
import app.betterhabits.ui.today.TodayViewModel

/** Builds every ViewModel from the [AppContainer]; the single place ViewModels are wired. */
object AppViewModelFactory {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val c = container()
            MainViewModel(c.userPreferencesRepository, c.householdSession, c.authRepository)
        }
        initializer {
            val c = container()
            ProfileViewModel(c.userPreferencesRepository, c.authRepository, c.profileRepository, c.householdSession.state)
        }

        initializer { SignInViewModel(container().authRepository) }
        initializer { SignUpViewModel(container().authRepository) }
        initializer { VerifyEmailViewModel(createSavedStateHandle(), container().authRepository) }
        initializer { ForgotPasswordViewModel(container().authRepository) }
        initializer { ChildSignInViewModel(container().authRepository) }

        initializer {
            val c = container()
            HouseholdSetupViewModel(c.householdRepository, c.householdSession, c.authRepository)
        }
        initializer { HouseholdViewModel(container().householdRepository, container().householdSession) }
        initializer { MemberDetailViewModel(createSavedStateHandle(), container().householdRepository, container().householdSession) }
        initializer { HouseholdSettingsViewModel(createSavedStateHandle(), container().householdRepository, container().householdSession) }

        initializer { val c = container(); TodayViewModel(c.choreRepository, c.householdRepository, c.householdSession) }
        initializer { val c = container(); ChoresViewModel(c.choreRepository, c.householdRepository, c.householdSession) }
        initializer { val c = container(); ChoreDetailViewModel(createSavedStateHandle(), c.choreRepository, c.householdRepository, c.householdSession) }
        initializer { val c = container(); ChoreEditorViewModel(createSavedStateHandle(), c.choreRepository, c.householdRepository, c.householdSession) }
        initializer { val c = container(); HistoryViewModel(c.choreRepository, c.householdRepository, c.householdSession) }
    }

    private fun CreationExtras.container(): AppContainer =
        (this[APPLICATION_KEY] as BetterHabitsApplication).container
}
