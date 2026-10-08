package app.betterhabits.ui.household

import app.betterhabits.data.auth.AuthUser
import app.betterhabits.data.household.HouseholdSession
import app.betterhabits.domain.error.AppError
import app.betterhabits.domain.model.HouseholdRole
import app.betterhabits.domain.model.PendingInvitation
import app.betterhabits.testing.FakeAuthRepository
import app.betterhabits.testing.FakeHouseholdRepository
import app.betterhabits.testing.FakeProfileRepository
import app.betterhabits.testing.FakeUserPreferencesRepository
import app.betterhabits.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class HouseholdViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val harry = AuthUser("harry", "harry@example.com", isChild = false)
    private val auth = FakeAuthRepository()
    private val repo = FakeHouseholdRepository { "harry" }.apply { names["harry"] = "Harry"; names["sarah"] = "Sarah" }
    private val prefs = FakeUserPreferencesRepository()

    private fun session(scope: CoroutineScope) =
        HouseholdSession(auth, repo, FakeProfileRepository { repo.names }, prefs, scope).also { auth.signInAs(harry) }

    @Test
    fun `setup creates a household and selects it`() = runTest {
        val session = session(mainDispatcherRule.appScope)
        val vm = HouseholdSetupViewModel(repo, session, auth)

        vm.create()
        assertTrue("empty name is rejected locally", vm.state.value.showNameValidation)

        vm.onNameChange("  Harry & Sarah ")
        vm.create()
        val id = assertNotNull(vm.state.value.completedHouseholdId).let { vm.state.value.completedHouseholdId!! }
        assertEquals(id, prefs.preferences.value.selectedHouseholdId)
        assertEquals("Harry & Sarah", repo.myHouseholds("harry").getOrThrow().single().household.name)
    }

    @Test
    fun `setup normalizes codes and maps join failures`() = runTest {
        val vm = HouseholdSetupViewModel(repo, session(mainDispatcherRule.appScope), auth)
        vm.onCodeChange("h7k4-p9qx-extra")
        assertEquals("H7K4P9QX", vm.state.value.code)

        vm.join()
        assertEquals(AppError.InviteInvalid, vm.state.value.joinError)

        val household = repo.seedHousehold("Sarah's", "sarah")
        repo.seedInviteCode(household, "H7K4P9QX", expiresAt = Instant.now().minusSeconds(1))
        vm.join()
        assertEquals(AppError.InviteExpired, vm.state.value.joinError)
    }

    @Test
    fun `accepting an invitation completes setup and declining removes it`() = runTest {
        val household = repo.seedHousehold("Sarah's", "sarah")
        val invite = PendingInvitation("i1", household, "Sarah's", "Sarah", HouseholdRole.MEMBER, Instant.now().plusSeconds(60))
        val other = invite.copy(id = "i2")
        repo.pendingForMe += listOf(invite, other)
        val vm = HouseholdSetupViewModel(repo, session(mainDispatcherRule.appScope), auth)
        assertEquals(2, vm.state.value.invitations.size)

        vm.respond(other, accept = false)
        assertEquals(listOf(invite), vm.state.value.invitations)

        vm.respond(invite, accept = true)
        assertEquals(household, vm.state.value.completedHouseholdId)
    }

    @Test
    fun `household screen loads the selected household with permissions`() = runTest {
        repo.seedHousehold("Home", "harry", "sarah" to HouseholdRole.MEMBER)
        val vm = HouseholdViewModel(repo, session(mainDispatcherRule.appScope))

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals("Home", state.details?.household?.name)
        assertEquals(listOf("Harry", "Sarah"), state.details?.members?.map { it.displayName })
        assertTrue(state.canInvite)
        assertTrue(state.canManageChildren)
        assertFalse("owner of a shared household can't just leave", state.canLeave)
    }

    @Test
    fun `invite by email validates then adds a pending invitation`() = runTest {
        repo.seedHousehold("Home", "harry")
        val vm = HouseholdViewModel(repo, session(mainDispatcherRule.appScope))

        vm.showInviteByEmail()
        vm.onInviteEmailChange("nope")
        vm.sendEmailInvite()
        assertTrue((vm.state.value.dialog as HouseholdDialog.InviteByEmail).showValidation)

        vm.onInviteEmailChange("Sarah@Example.com")
        vm.sendEmailInvite()
        assertEquals(HouseholdDialog.None, vm.state.value.dialog)
        assertEquals("sarah@example.com", vm.state.value.invitations.single().email)
    }

    @Test
    fun `adding a child shows their username and lists them`() = runTest {
        repo.seedHousehold("Home", "harry")
        val vm = HouseholdViewModel(repo, session(mainDispatcherRule.appScope))

        vm.showAddChild()
        vm.onChildNameChange("Alex")
        vm.onChildPinChange("123")
        vm.createChild()
        assertTrue("short PIN rejected", (vm.state.value.dialog as HouseholdDialog.AddChild).showValidation)

        vm.onChildPinChange("123456")
        vm.createChild()
        assertEquals(HouseholdDialog.ChildCreated("Alex", "alex-ab12"), vm.state.value.dialog)
        assertTrue(vm.state.value.details!!.members.any { it.displayName == "Alex" && it.isChildAccount })
    }

    @Test
    fun `invite codes can be created and revoked`() = runTest {
        repo.seedHousehold("Home", "harry")
        val vm = HouseholdViewModel(repo, session(mainDispatcherRule.appScope))
        vm.createInviteCode()
        val code = vm.state.value.activeCodes.single()
        vm.revokeInviteCode(code)
        assertTrue(vm.state.value.activeCodes.isEmpty())
    }

    @Test
    fun `server errors become snackbar messages`() = runTest {
        repo.seedHousehold("Home", "harry")
        val vm = HouseholdViewModel(repo, session(mainDispatcherRule.appScope))
        repo.nextError = AppError.PermissionDenied
        vm.createInviteCode()
        assertEquals(AppError.PermissionDenied, vm.state.value.message)
        vm.messageShown()
        assertEquals(null, vm.state.value.message)
    }
}
