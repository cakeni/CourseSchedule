package com.courseschedule.ui.assistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.courseschedule.databinding.ActivityStudyTasksBinding

internal object StudyMotion {
    private val ease = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    fun enterPage(binding: ActivityStudyTasksBinding): AnimatorSet? {
        val results = if (binding.taskEmpty.visibility == View.VISIBLE) binding.taskEmpty else binding.taskRows
        val targets = listOf(binding.taskHeader, binding.tvTaskReminderStatus, binding.btnTasksToday,
            binding.btnTasksUpcoming, binding.btnTasksPending, binding.taskSearch, binding.taskListHeader, results)
        fun settle() = targets.forEach { it.alpha = 1f; it.translationY = 0f }
        targets.forEach { it.animate().cancel() }
        settle()
        if (!ValueAnimator.areAnimatorsEnabled()) return null

        val animations = mutableListOf<Animator>()
        fun layer(view: View, delay: Long, distanceDp: Float, opacity: Float, duration: Long = 380L) {
            if (view.visibility != View.VISIBLE) return
            view.alpha = opacity
            view.translationY = distanceDp * view.resources.displayMetrics.density
            animations += AnimatorSet().apply {
                playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, opacity, 1f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.translationY, 0f))
                startDelay = delay
                this.duration = duration
                interpolator = ease
            }
        }
        layer(binding.taskHeader, 0L, 8f, 0.85f, 300L)
        layer(binding.tvTaskReminderStatus, 0L, 8f, 0.85f, 300L)
        layer(binding.btnTasksToday, 30L, 20f, 0.7f)
        layer(binding.btnTasksUpcoming, 60L, 20f, 0.7f)
        layer(binding.btnTasksPending, 90L, 20f, 0.7f)
        layer(binding.taskSearch, 80L, 12f, 0.8f)
        layer(binding.taskListHeader, 110L, 12f, 0.85f)
        layer(results, 130L, 20f, 0.65f)
        return AnimatorSet().apply {
            playTogether(animations)
            // Cancellation also restores every layer when a filter or navigation interrupts entry.
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { settle() }
            })
            start()
        }
    }

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
