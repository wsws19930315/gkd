package li.gkd.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent

fun Context.tryStartActivity(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
        LogUtils.d("tryStartActivity", e)
        toast("跳转失败\n" + (e.message ?: e.stackTraceToString()))
    }
}

val Intent.extraCptName: ComponentName?
    get() = if (AndroidTarget.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_COMPONENT_NAME) as? ComponentName?
    }
