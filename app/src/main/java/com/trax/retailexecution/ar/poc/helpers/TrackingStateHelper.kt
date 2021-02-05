package com.trax.retailexecution.ar.poc.helpers

import android.view.WindowManager
import com.google.ar.core.TrackingState
import com.trax.retailexecution.ar.poc.MainActivity

class TrackingStateHelper(mainActivity: MainActivity) {
    private var activity = mainActivity
    private var previousTrackingState: TrackingState? = null

    fun updateKeepScreenOnFlag(trackingState: TrackingState) {
        if (trackingState == previousTrackingState) {
            return
        }
        previousTrackingState = trackingState
        when (trackingState) {
            TrackingState.PAUSED, TrackingState.STOPPED -> activity!!.runOnUiThread { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            TrackingState.TRACKING -> activity!!.runOnUiThread { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
}