import android.app.Application
import android.app.Activity
import android.os.Bundle
import java.lang.ref.WeakReference

open class BaseApplication : Application() {
    companion object {
        lateinit var instance: Application
        private const val TAG = "ScreenshotDetector"

        @Volatile
        private var foregroundActivityRef: WeakReference<Activity>? = null

        val foregroundActivity: Activity?
            get() = foregroundActivityRef?.get()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                foregroundActivityRef = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (foregroundActivityRef?.get() === activity) {
                    foregroundActivityRef = null
                }
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (foregroundActivityRef?.get() === activity) {
                    foregroundActivityRef = null
                }
            }
        })
    }
}
