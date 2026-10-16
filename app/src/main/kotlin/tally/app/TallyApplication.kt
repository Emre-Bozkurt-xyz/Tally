package tally.app

import android.app.Application
import android.content.Context
import tally.app.quickentry.QuickAddShortcut

class TallyApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        QuickAddShortcut.publish(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as TallyApplication).container
