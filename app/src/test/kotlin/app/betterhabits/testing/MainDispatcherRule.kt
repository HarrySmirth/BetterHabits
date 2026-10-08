package app.betterhabits.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Routes Dispatchers.Main (used by viewModelScope) to an eager test dispatcher, and provides
 * [appScope] on the same dispatcher for app-wide objects such as HouseholdSession.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    lateinit var appScope: CoroutineScope
        private set

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
        appScope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    override fun finished(description: Description) {
        appScope.cancel()
        Dispatchers.resetMain()
    }
}
