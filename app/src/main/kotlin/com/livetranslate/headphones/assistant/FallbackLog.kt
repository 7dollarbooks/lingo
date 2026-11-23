package com.livetranslate.headphones.assistant

import android.util.Log

/** One line per routed question. Filter with `adb logcat -s LingoRoute`. */
object FallbackLog {
    private const val TAG = "LingoRoute"

    fun route(feature: String, questionType: String, reason: String, model: String, query: String) {
        val q = query.replace('\n', ' ').take(80)
        Log.i(TAG, "feature=$feature type=$questionType reason=$reason model=$model q=$q")
    }
}
