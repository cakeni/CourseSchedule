package com.courseschedule.ui.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import com.courseschedule.databinding.ActivitySettingsBinding
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

/** Section panels unfold vertically inside their final layout slots. */
internal class SettingsEntranceMotion(private val binding: ActivitySettingsBinding) {
    private val panelEase = PathInterpolator(.25f, .75f, .25f, 1f)
    private val contentEase = PathInterpolator(.4f, 0f, .2f, 1f)
    private val panels = mutableListOf<Panel>()
    private var pendingDraw: OneShotPreDrawListener? = null
    private var animation: ValueAnimator? = null

    fun enter() {
        settle()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        pendingDraw = binding.settingsContent.doOnPreDraw {
            pendingDraw = null
            if (!ValueAnimator.areAnimatorsEnabled()) return@doOnPreDraw
            val visible = Rect()
            val minVisibleHeight = (24f * binding.root.resources.displayMetrics.density).roundToInt()
            for (index in 0 until binding.settingsContent.childCount) {
                val card = binding.settingsContent.getChildAt(index) as? MaterialCardView ?: continue
                if (card === binding.cardOpenSource || card.childCount == 0 ||
                    !card.getGlobalVisibleRect(visible) || visible.height() < minVisibleHeight) continue
                panels += Panel(card, card.getChildAt(0), panels.size * 110f)
            }
            if (panels.isEmpty()) return@doOnPreDraw
            panels.forEach { it.prepare() }

            fun frame(elapsed: Float) {
                panels.forEach { panel ->
                    val localTime = elapsed - panel.start
                    val expansion = panelEase.getInterpolation((localTime / 380f).coerceIn(0f, 1f))
                    panel.height = (panel.startHeight + (panel.card.height - panel.startHeight) * expansion).roundToInt()
                    panel.card.invalidateOutline()
                    panel.content.alpha = panel.originalAlpha * contentEase.getInterpolation(((localTime - 120f) / 260f).coerceIn(0f, 1f))
                }
            }

            frame(0f)
            val end = panels.last().start + 380f
            animation = ValueAnimator.ofFloat(0f, end).apply {
                duration = end.toLong()
                interpolator = LinearInterpolator()
                addUpdateListener { frame(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animator: Animator) {
                        if (animation === animator) {
                            animation = null
                            restorePanels()
                        }
                    }
                })
                start()
            }
        }
    }

    fun settle() {
        pendingDraw?.removeListener()
        pendingDraw = null
        val running = animation
        animation = null
        running?.cancel()
        restorePanels()
    }

    private fun restorePanels() {
        panels.forEach { it.restore() }
        panels.clear()
    }

    private class Panel(val card: MaterialCardView, val content: View, val start: Float) {
        private val originalOutline = card.outlineProvider
        private val originalClip = card.clipToOutline
        val originalAlpha = content.alpha
        val startHeight = minOf((36f * card.resources.displayMetrics.density).roundToInt(), card.height / 2)
        var height = startHeight
        private val outline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, card.width, height, minOf(card.radius, height / 2f))
                outline.alpha = 1f
            }
        }

        fun prepare() {
            card.outlineProvider = outline
            card.clipToOutline = true
            content.alpha = 0f
        }

        fun restore() {
            card.outlineProvider = originalOutline
            card.clipToOutline = originalClip
            card.invalidateOutline()
            content.alpha = originalAlpha
        }
    }
}
