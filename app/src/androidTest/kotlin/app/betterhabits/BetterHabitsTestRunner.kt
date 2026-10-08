package app.betterhabits

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.AndroidJUnitRunner
import app.betterhabits.di.AppContainer
import app.betterhabits.testing.FakeAppContainer
import kotlinx.coroutines.MainScope

class TestBetterHabitsApplication : BetterHabitsApplication() {
    override fun createContainer(): AppContainer = FakeAppContainer(MainScope())
}

/** Runs instrumented tests against [TestBetterHabitsApplication] (fakes, no network). */
class BetterHabitsTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestBetterHabitsApplication::class.java.name, context)
}

/** Fresh fake backend for each test; call before launching the activity. */
fun resetFakeContainer(): FakeAppContainer {
    val app = ApplicationProvider.getApplicationContext<BetterHabitsApplication>()
    return FakeAppContainer(MainScope()).also { app.container = it }
}
