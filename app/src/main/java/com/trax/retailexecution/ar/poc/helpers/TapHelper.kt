package com.trax.retailexecution.ar.poc.helpers

import android.content.Context
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue

class TapHelper(context: Context?): View.OnTouchListener {
    private var gestureDetector: GestureDetector? = null
    private val queuedSingleTaps: BlockingQueue<MotionEvent> = ArrayBlockingQueue(16)

    init {
        gestureDetector = GestureDetector(
                context,
                object : SimpleOnGestureListener() {
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        queuedSingleTaps.offer(e)
                        return true
                    }

                    override fun onDown(e: MotionEvent): Boolean {
                        return true
                    }
                })
    }

    fun poll(): MotionEvent? {
        return queuedSingleTaps.poll()
    }

    override fun onTouch(view: View?, motionEvent: MotionEvent?): Boolean {
        return gestureDetector!!.onTouchEvent(motionEvent)
    }
}