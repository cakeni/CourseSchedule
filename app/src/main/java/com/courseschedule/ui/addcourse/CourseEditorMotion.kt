package com.courseschedule.ui.addcourse

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet

internal object CourseEditorMotion {
    private val ease = PathInterpolator(.22f, 1f, .36f, 1f)

    fun expand(container: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled() || !container.isLaidOut) return
        TransitionManager.endTransitions(container)
        TransitionManager.beginDelayedTransition(container, TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(ChangeBounds())
            addTransition(Fade().setDuration(160))
            duration = 220
            interpolator = ease
        })
    }

    fun indicator(view: View, position: Float, animate: Boolean) {
        view.animate().cancel()
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            view.animate().translationX(position).setDuration(220).setInterpolator(ease).start()
        } else view.translationX = position
    }

    fun chevron(view: View, expanded: Boolean, animate: Boolean) {
        view.animate().cancel()
        val angle = if (expanded) 90f else 0f
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            view.animate().rotation(angle).setDuration(220).setInterpolator(ease).start()
        } else view.rotation = angle
    }

    fun enter(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.alpha = .65f
        view.translationY = 6 * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(ease).start()
    }
}
