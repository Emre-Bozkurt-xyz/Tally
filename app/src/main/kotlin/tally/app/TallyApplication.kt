package tally.app

import android.app.Application
import android.content.Context
import tally.app.capture.CaptureNotifier
import tally.app.quickentry.QuickAddShortcut

class TallyApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        QuickAddShortcut.publish(this)
        CaptureNotifier.createChannel(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as TallyApplication).container
