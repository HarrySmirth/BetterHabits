package app.betterhabits

import android.app.Application
import app.betterhabits.di.AppContainer
import app.betterhabits.di.DefaultAppContainer

class BetterHabitsApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
