package ph.marbingo.field

import android.app.Application
import android.content.Context
import androidx.work.Configuration

class MarbingoApp : Application(), Configuration.Provider {
    val graph: AppGraph by lazy { AppGraph(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        graph.onAppStart()
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as MarbingoApp).graph
    }
}
