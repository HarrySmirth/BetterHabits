package app.betterhabits.ui.auth

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable

@Serializable data object SignInRoute
@Serializable data object SignUpRoute
@Serializable data class VerifyEmailRoute(val email: String)
@Serializable data object ForgotPasswordRoute
@Serializable data object ChildSignInRoute

/** Signed-out flow. Signing in changes the session state, which swaps this out at the root. */
@Composable
fun AuthNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = SignInRoute) {
        composable<SignInRoute> {
            SignInScreen(
                onCreateAccount = { navController.navigate(SignUpRoute) },
                onForgotPassword = { navController.navigate(ForgotPasswordRoute) },
                onChildSignIn = { navController.navigate(ChildSignInRoute) },
                onVerifyEmail = { navController.navigate(VerifyEmailRoute(it)) },
            )
        }
        composable<SignUpRoute> {
            SignUpScreen(
                onBack = navController::popBackStack,
                onVerifyEmail = { email ->
                    navController.navigate(VerifyEmailRoute(email)) { popUpTo(SignInRoute) }
                },
            )
        }
        composable<VerifyEmailRoute> { VerifyEmailScreen(onBack = navController::popBackStack) }
        composable<ForgotPasswordRoute> { ForgotPasswordScreen(onBack = navController::popBackStack) }
        composable<ChildSignInRoute> { ChildSignInScreen(onBack = navController::popBackStack) }
    }
}
