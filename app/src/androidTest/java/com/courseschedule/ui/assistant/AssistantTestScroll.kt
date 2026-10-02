package com.courseschedule.ui.assistant

import android.graphics.Rect
import android.view.View
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom

internal fun historyScrollTo() = object : ViewAction {
    override fun getDescription() = "Scroll the assistant history to the requested content"
    override fun getConstraints() = isAssignableFrom(View::class.java)
    override fun perform(controller: UiController, view: View) {
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        controller.loopMainThreadUntilIdle()
    }
}
