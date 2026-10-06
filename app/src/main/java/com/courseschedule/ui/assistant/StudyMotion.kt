package com.courseschedule.ui.assistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.CompoundButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.courseschedule.databinding.ActivityStudyTasksBinding
import com.courseschedule.R

internal object StudyMotion {
    private val ease = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    fun enterPage(binding: ActivityStudyTasksBinding): AnimatorSet? {
        val results = if (binding.taskEmpty.visibility == View.VISIBLE) binding.taskEmpty else binding.taskRows
        val targets = listOf(binding.taskHeader, binding.tvTaskReminderStatus, binding.taskFilters,
            binding.taskSearch, binding.taskListHeader, results)
        fun settle() = targets.forEach { it.alpha = 1f; it.translationY = 0f }
        targets.forEach { it.animate().cancel() }
        settle()
        if (!ValueAnimator.areAnimatorsEnabled()) return null

        val animations = mutableListOf<Animator>()
        fun layer(view: View, delay: Long, distanceDp: Float, opacity: Float, duration: Long = 240L) {
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
        layer(binding.taskFilters, 0L, 6f, 0.85f, 220L)
        layer(binding.taskSearch, 0L, 6f, 0.85f, 220L)
        layer(binding.taskListHeader, 30L, 8f, 0.85f)
        layer(results, 30L, 8f, 0.85f)
        return AnimatorSet().apply {
            playTogether(animations)
            // Cancellation also restores every layer when a filter or navigation interrupts entry.
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { settle() }
            })
            start()
        }
    }

    fun appear(view: View) {
        view.animate().cancel()
        view.alpha = 1f; view.translationY = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.alpha = 0.9f
        view.animate().alpha(1f).translationY(0f).setStartDelay(0)
            .setDuration(120L).setInterpolator(ease).start()
    }

    fun panel(container: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled() || !container.isLaidOut) return
        TransitionManager.beginDelayedTransition(container, TransitionSet().apply {
            addTransition(ChangeBounds()); addTransition(Fade())
            ordering = TransitionSet.ORDERING_TOGETHER
            duration = 200L; interpolator = ease
        })
    }

    fun finishRow(view: View, completed: Boolean, done: () -> Unit): Animator? {
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow || view.height <= 0) { done(); return null }
        view.animate().cancel()
        val height = view.height
        val checkbox = view.findViewById<CompoundButton>(R.id.checkTaskDone)
        val icon = Rect(checkbox.buttonDrawable?.bounds ?: Rect(0, 0, checkbox.width, checkbox.height))
        (view as ViewGroup).offsetDescendantRectToMyCoords(checkbox, icon)
        val feedback = TaskCompletionCapsule(view.context, completed, RectF(icon)).apply { setBounds(0, 0, view.width, height) }
        val kind = view.findViewById<TextView>(R.id.tvTaskKind)
        val originalKind = kind.text
        val originalInk = kind.textColors
        val originalTint = kind.backgroundTintList
        kind.text = if (completed) "已完成" else "已恢复"
        kind.setTextColor(ContextCompat.getColor(view.context, if (completed) R.color.task_success else R.color.reference_blue_accent))
        kind.backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(view.context,
            if (completed) R.color.task_success_surface else R.color.reference_blue_surface))
        checkbox.alpha = 0f
        view.overlay.add(feedback)
        val group = view.parent as? ViewGroup
        val list = group?.parent as? ViewGroup
        val header = if (group?.childCount == 1 && list != null) list.getChildAt(list.indexOfChild(group) - 1) else null
        val headerHeight = header?.height ?: 0
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                feedback.progress = p
                val shrink = com.courseschedule.ui.SchedulePageMotion.phase(((p - .55f) / .45f).coerceIn(0f, 1f))
                view.alpha = 1f - shrink
                val nextHeight = (height * (1f - shrink)).toInt()
                if (view.layoutParams.height != nextHeight) view.layoutParams = view.layoutParams.apply { this.height = nextHeight }
                header?.apply {
                    alpha = 1f - shrink
                    val nextHeaderHeight = (headerHeight * (1f - shrink)).toInt()
                    if (layoutParams.height != nextHeaderHeight) layoutParams = layoutParams.apply { this.height = nextHeaderHeight }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.overlay.remove(feedback)
                    checkbox.alpha = 1f
                    kind.text = originalKind
                    kind.setTextColor(originalInk)
                    kind.backgroundTintList = originalTint
                    view.alpha = 1f
                    view.layoutParams = view.layoutParams.apply { this.height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    header?.apply { alpha = 1f; layoutParams = layoutParams.apply { this.height = ViewGroup.LayoutParams.WRAP_CONTENT } }
                    done()
                }
            })
            start()
        }
    }
}
