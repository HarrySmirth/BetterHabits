package app.betterhabits

import android.app.Application
import androidx.annotation.VisibleForTesting
import app.betterhabits.di.AppContainer
import app.betterhabits.di.DefaultAppContainer

open class BetterHabitsApplication : Application() {

    lateinit var container: AppContainer
        @VisibleForTesting internal set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
    }

    /** Instrumented tests override this to run the real UI against fake repositories. */
    protected open fun createContainer(): AppContainer = DefaultAppContainer(this)
}
