package com.courseschedule.ui.assistant

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet

internal object StudyMotion {
    private val ease = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    fun appear(view: View, distanceDp: Float = 6f) {
        view.animate().cancel()
        view.alpha = 1f; view.translationY = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.alpha = 0.72f
        view.translationY = distanceDp * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setStartDelay(0)
            .setDuration(240L).setInterpolator(ease).start()
    }

    fun panel(container: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled() || !container.isLaidOut) return
        TransitionManager.beginDelayedTransition(container, TransitionSet().apply {
            addTransition(ChangeBounds()); addTransition(Fade())
            ordering = TransitionSet.ORDERING_TOGETHER
            duration = 200L; interpolator = ease
        })
    }

    fun finishRow(view: View, done: () -> Unit) {
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow) { done(); return }
        view.animate().cancel()
        view.animate().alpha(0f).translationY(-3f * view.resources.displayMetrics.density)
            .setStartDelay(60L).setDuration(160L).setInterpolator(ease)
            .withEndAction(done).start()
    }
}
