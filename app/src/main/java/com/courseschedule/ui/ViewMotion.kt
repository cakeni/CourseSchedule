package com.courseschedule.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import androidx.annotation.IdRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.transition.TransitionManager
import androidx.core.view.doOnPreDraw
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import com.courseschedule.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.R as MaterialR

private val pressReleaseInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)

internal fun ViewGroup.enterReferenceContent() {
    val children = (0 until childCount).map(::getChildAt)
    children.forEach { it.animate().cancel(); it.alpha = 1f; it.translationX = 0f; it.translationY = 0f; it.scaleX = 1f; it.scaleY = 1f }
    if (!ValueAnimator.areAnimatorsEnabled()) return
    children.filter { it.visibility == View.VISIBLE }.forEach { it.alpha = .88f; it.translationY = 6f * resources.displayMetrics.density }
    doOnPreDraw {
        children.forEachIndexed { index, child ->
            child.animate().alpha(1f).translationY(0f).setStartDelay((index * 20L).coerceAtMost(80))
                .setDuration(300L).setInterpolator(pressReleaseInterpolator).start()
        }
    }
}

fun View.installPressScale(pressedScale: Float = 0.98f) {
    fun scale(value: Float, duration: Long) = AnimatorSet().apply {
        playTogether(ObjectAnimator.ofFloat(this@installPressScale, View.SCALE_X, value),
            ObjectAnimator.ofFloat(this@installPressScale, View.SCALE_Y, value))
        this.duration = duration
        interpolator = pressReleaseInterpolator
    }
    // The framework handles drag cancellation, disabled states and reduced motion.
    // No touch listener: click, scrolling and custom gestures retain their ownership.
    stateListAnimator = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), scale(pressedScale, 110L))
        addState(intArrayOf(), scale(1f, 220L))
    }
}

fun BottomNavigationView.selectItemWithoutAnimation(@IdRes itemId: Int, animateCapsule: Boolean = true) {
    selectedItemId = itemId
    (getChildAt(0) as? ViewGroup)?.let(TransitionManager::endTransitions)
    ReferenceNavigation.select(this, animate = animateCapsule)
}

fun BottomNavigationView.stabilizeActiveIndicatorSize() {
    isItemActiveIndicatorEnabled = false
    ReferenceNavigation.install(this)
    for (index in 0 until menu.size()) {
        val indicator = findViewById<View>(menu.getItem(index).itemId)
            ?.findViewById<View>(MaterialR.id.navigation_bar_item_active_indicator_view)
            ?: continue
        indicator.layoutParams = indicator.layoutParams.apply {
            width = itemActiveIndicatorWidth
            height = itemActiveIndicatorHeight
        }
    }
}

fun View?.playNavigationMotion() {
    val item = this ?: return
    val icon = item.findViewById<ImageView>(MaterialR.id.navigation_bar_item_icon_view) ?: return
    val assets = when (item.id) {
        R.id.nav_home -> R.drawable.ic_schedule to R.drawable.avd_nav_schedule
        R.id.nav_study -> R.drawable.ic_study_navigation to R.drawable.avd_nav_todo
        R.id.nav_assistant -> R.drawable.ic_assistant_navigation to R.drawable.avd_nav_assistant
        R.id.nav_import -> R.drawable.ic_import to R.drawable.avd_nav_import
        R.id.nav_settings -> R.drawable.ic_settings to R.drawable.avd_nav_settings
        else -> return
    }
    (icon.drawable as? Animatable)?.stop()
    icon.animate().cancel()
    icon.apply {
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
        rotation = 0f
        rotationY = 0f
    }

    var parent = item.parent
    while (parent is View && parent !is BottomNavigationView) parent = (parent as View).parent
    (parent as? BottomNavigationView)?.let(ReferenceNavigation::select)
    icon.imageTintList = (parent as? BottomNavigationView)?.itemIconTintList
    val resting = AppCompatResources.getDrawable(item.context, assets.first)
    if (!ValueAnimator.areAnimatorsEnabled()) {
        icon.setImageDrawable(resting)
        return
    }
    val motion = AnimatedVectorDrawableCompat.create(item.context, assets.second) ?: return
    motion.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            // An interrupted animation must never replace a newer one.
            if (icon.drawable === motion) icon.setImageDrawable(resting)
        }
    })
    icon.setImageDrawable(motion)
    motion.start()
}
