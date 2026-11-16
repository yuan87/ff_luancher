package com.ff.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ff.launcher.util.Logger

/**
 * Trampoline activity exposed with [Intent.CATEGORY_LAUNCHER] so that other launchers
 * (e.g. Niagara Launcher, Pixel Launcher) display FF Launcher in their app drawers.
 *
 * When tapped, it immediately launches the primary home activity as [Intent.CATEGORY_HOME]
 * and finishes itself with [finishAndRemoveTask] so it never leaves a task card in
 * Android's Recent Apps carousel.
 */
class AppEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("AppEntryActivity") { "onCreate: trampoline invoked, redirecting to MainActivity" }
        val homeIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        startActivity(homeIntent)
        Logger.i("AppEntryActivity") { "finishing trampoline activity" }
        finish()
    }
}
