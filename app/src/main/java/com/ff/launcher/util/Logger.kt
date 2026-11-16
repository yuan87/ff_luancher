package com.ff.launcher.util

import android.util.Log
import com.ff.launcher.BuildConfig

/**
 * Thin logging facade that compiles out [d] and [i] calls in release builds.
 *
 * [w] (warnings) and [e] (errors) remain active in all builds because they indicate
 * problems worth investigating from crash reports / logcat captures.
 *
 * Usage:
 *   Logger.d(TAG) { "widgetRequests received: $request" }
 *   Logger.w(TAG, "startListening failed", throwable)
 */
object Logger {
    /** Debug-level log. Compiled out in release builds. */
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }

    /** Info-level log. Compiled out in release builds. */
    inline fun i(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.i(tag, message())
    }

    /** Warning-level log. Always active. */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
    }

    /** Error-level log. Always active. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}
