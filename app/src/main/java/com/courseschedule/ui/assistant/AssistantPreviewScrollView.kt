package com.courseschedule.ui.assistant

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.TextView
import androidx.core.widget.NestedScrollView

/** Keeps preview swipes inside the card while preserving long-press text selection. */
class AssistantPreviewScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {
    init { isNestedScrollingEnabled = false }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Selectable TextView requests exclusivity on DOWN, before distinguishing a swipe from a long press.
        if (!disallowIntercept || (getChildAt(0) as? TextView)?.hasSelection() == true) {
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }
}
