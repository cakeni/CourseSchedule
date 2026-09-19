package com.courseschedule.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.IdRes
import androidx.transition.TransitionManager
import com.courseschedule.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.R as MaterialR

private val pressReleaseInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)

fun View.installPressScale(pressedScale: Float = 0.98f) {
    setOnTouchListener { target, event ->
        val targetScale = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressedScale
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
            else -> return@setOnTouchListener false
        }
        target.animate().cancel()
        target.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .setDuration(if (targetScale < 1f) 115L else 245L)
            .setInterpolator(pressReleaseInterpolator)
            .start()
        false
    }
}

fun BottomNavigationView.selectItemWithoutAnimation(@IdRes itemId: Int) {
    selectedItemId = itemId
    (getChildAt(0) as? ViewGroup)?.let(TransitionManager::endTransitions)
}

fun BottomNavigationView.stabilizeActiveIndicatorSize() {
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
    val item = this
    if (item == null) return
    val icon = item.findViewById<View>(MaterialR.id.navigation_bar_item_icon_view) ?: item
    val density = item.resources.displayMetrics.density
    icon.animate().cancel()
    icon.apply {
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
        rotation = 0f
        rotationY = 0f
    }

    when (item.id) {
        R.id.nav_home -> icon.apply {
            cameraDistance = 8_000f * density
            animate()
                .rotationY(-38f)
                .scaleX(0.91f)
                .scaleY(0.91f)
                .setDuration(280L)
                .setInterpolator(pressReleaseInterpolator)
                .withLayer()
                .withEndAction {
                    animate()
                        .rotationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(520L)
                        .setInterpolator(OvershootInterpolator(0.7f))
                        .withLayer()
                        .start()
                }
                .start()
        }

        R.id.nav_import -> {
            icon.animate()
                .translationY(4f * density)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(220L)
                .setInterpolator(pressReleaseInterpolator)
                .withLayer()
                .withEndAction {
                    icon.animate()
                        .translationY(-8f * density)
                        .scaleX(1.07f)
                        .scaleY(1.07f)
                        .setDuration(300L)
                        .setInterpolator(pressReleaseInterpolator)
                        .withLayer()
                        .withEndAction {
                            icon.animate()
                                .translationY(0f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(430L)
                                .setInterpolator(OvershootInterpolator(0.68f))
                                .withLayer()
                                .start()
                        }
                        .start()
                }
                .start()
        }

        R.id.nav_settings -> {
            icon.animate()
                .rotation(88f)
                .scaleX(0.93f)
                .scaleY(0.93f)
                .setDuration(400L)
                .setInterpolator(pressReleaseInterpolator)
                .withLayer()
                .withEndAction {
                    icon.animate()
                        .rotation(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(520L)
                        .setInterpolator(OvershootInterpolator(0.6f))
                        .withLayer()
                        .start()
                }
                .start()
        }

        else -> {
            icon.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(260L)
                .setInterpolator(pressReleaseInterpolator)
                .withEndAction {
                    icon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(520L)
                        .setInterpolator(OvershootInterpolator(0.7f))
                        .start()
                }
                .start()
        }
    }
}
