package app.betterhabits.ui.auth

import androidx.lifecycle.SavedStateHandle
import app.betterhabits.data.auth.AuthState
import app.betterhabits.domain.error.AppError
import app.betterhabits.testing.FakeAuthRepository
import app.betterhabits.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val auth = FakeAuthRepository().apply { addAccount("harry@example.com", "correct-horse") }

    @Test
    fun `invalid sign-in input shows validation without calling the server`() = runTest {
        val vm = SignInViewModel(auth)
        vm.onEmailChange("not-an-email")
        vm.submit()
        assertTrue(vm.state.value.showValidation)
        assertEquals(AuthState.SignedOut, auth.authState.value)
    }

    @Test
    fun `wrong password maps to a friendly error and right password signs in`() = runTest {
        val vm = SignInViewModel(auth)
        vm.onEmailChange("harry@example.com")
        vm.onPasswordChange("nope")
        vm.submit()
        assertEquals(AppError.InvalidCredentials, vm.state.value.error)

        vm.onPasswordChange("correct-horse")
        assertNull("typing clears the error", vm.state.value.error)
        vm.submit()
        assertTrue(auth.authState.value is AuthState.SignedIn)
    }

    @Test
    fun `unconfirmed account is routed to code verification`() = runTest {
        auth.accounts["new@example.com"] = auth.accounts.getValue("harry@example.com").copy(confirmed = false)
        val vm = SignInViewModel(auth)
        vm.onEmailChange("new@example.com")
        vm.onPasswordChange("correct-horse")
        vm.submit()
        assertEquals("new@example.com", vm.state.value.verifyEmail)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `sign up then verify the emailed code`() = runTest {
        val signUp = SignUpViewModel(auth)
        signUp.onNameChange("Sarah")
        signUp.onEmailChange("sarah@example.com")
        signUp.onPasswordChange("short")
        signUp.submit()
        assertTrue("password too short", signUp.state.value.showValidation)
        assertNull(signUp.state.value.verifyEmail)

        signUp.onPasswordChange("long enough")
        signUp.submit()
        assertEquals("sarah@example.com", signUp.state.value.verifyEmail)

        val verify = VerifyEmailViewModel(SavedStateHandle(mapOf("email" to "sarah@example.com")), auth)
        verify.onCodeChange("000000")
        assertEquals(AppError.InvalidCode, verify.state.value.error)
        assertEquals("", verify.state.value.code)

        verify.onCodeChange("12-34 56") // pasted with separators; auto-submits at 6 digits
        assertTrue(auth.authState.value is AuthState.SignedIn)
    }

    @Test
    fun `password reset is a two-step flow`() = runTest {
        val vm = ForgotPasswordViewModel(auth)
        vm.onEmailChange("harry@example.com")
        vm.sendCode()
        assertTrue(vm.state.value.codeSent)

        vm.onCodeChange(auth.signUpCode)
        vm.onPasswordChange("brand new password")
        vm.resetPassword()
        assertTrue(auth.authState.value is AuthState.SignedIn)
        assertEquals("brand new password", auth.accounts.getValue("harry@example.com").password)
    }
}
