package app.betterhabits.data.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/** Being offline must never sign the user out: signing out wipes their local data. */
class AuthStateTest {

    private val harry = AuthUser("harry", "harry@example.com", isChild = false)

    @Test
    fun `a remembered user opens straight into the app while the session is still loading`() {
        assertEquals(AuthState.SignedIn(harry), resolveAuthState(SessionSignal.Initializing, lastUser = harry))
        assertEquals(AuthState.Loading, resolveAuthState(SessionSignal.Initializing, lastUser = null))
    }

    @Test
    fun `an expired token that can't be refreshed offline keeps the user signed in`() {
        assertEquals(AuthState.SignedIn(harry), resolveAuthState(SessionSignal.RefreshFailed, lastUser = harry))
    }

    @Test
    fun `only a real sign-out or revoked session signs out`() {
        assertEquals(AuthState.SignedOut, resolveAuthState(SessionSignal.NotAuthenticated, lastUser = harry))
    }

    @Test
    fun `a confirmed session wins over the remembered user`() {
        val sarah = AuthUser("sarah", "sarah@example.com", isChild = false)
        assertEquals(AuthState.SignedIn(sarah), resolveAuthState(SessionSignal.Authenticated(sarah), lastUser = harry))
    }
}
