package com.courseschedule.ui.importdata

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
import com.courseschedule.databinding.ActivityImportBinding
import kotlin.math.roundToInt

/** The primary entry grows within its final slot; content never scales with the shape. */
internal class ImportEntranceMotion(private val binding: ActivityImportBinding) {
    private val card = binding.cardImportSchool
    private var originalOutline = card.outlineProvider
    private var originalClip = card.clipToOutline
    private var outlineOverridden = false
    private val ease = PathInterpolator(.22f, 1f, .36f, 1f)
    private val secondaryEase = PathInterpolator(.4f, 0f, .2f, 1f)
    private val outlineBounds = Rect()
    private var outlineRadius = 0f
    private var pendingDraw: OneShotPreDrawListener? = null
    private var animation: ValueAnimator? = null
    private val reveals = listOf(
        Reveal(binding.tvSchoolImportTitle, 140f, 140f),
        Reveal(binding.tvSchoolImportDescription, 180f, 150f),
        Reveal(binding.schoolImportArrow, 240f, 100f),
        Reveal(binding.tvOtherImportMethods, 260f, 100f),
        Reveal(binding.cardImportAssistant, 360f, 280f, secondaryEase),
        Reveal(binding.cardImportJson, 460f, 280f, secondaryEase),
        Reveal(binding.cardImportText, 560f, 280f, secondaryEase),
        Reveal(binding.cardImportTips, 680f, 180f, secondaryEase),
    )
    private val entryDuration = reveals.maxOf { it.start + it.duration }
    private val expandingOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(outlineBounds, outlineRadius)
            outline.alpha = 1f
        }
    }

    fun enter() {
        settle()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        reveals.forEach { it.view.alpha = 0f }
        pendingDraw = card.doOnPreDraw {
            pendingDraw = null
            if (card.width == 0 || card.height == 0 || !ValueAnimator.areAnimatorsEnabled()) {
                settle()
                return@doOnPreDraw
            }
            val source = Rect(0, 0, binding.schoolIconContainer.width, binding.schoolIconContainer.height)
            card.offsetDescendantRectToMyCoords(binding.schoolIconContainer, source)
            val inset = (5f * card.resources.displayMetrics.density).roundToInt()
            source.inset(-inset, -inset)
            if (!source.intersect(0, 0, card.width, card.height)) {
                settle()
                return@doOnPreDraw
            }
            val sourceRadius = minOf(source.width(), source.height()) / 2f
            // MaterialCardView finishes configuring its outline during layout.
            originalOutline = card.outlineProvider
            originalClip = card.clipToOutline
            outlineOverridden = true
            card.outlineProvider = expandingOutline
            card.clipToOutline = true

            fun frame(elapsed: Float) {
                val expansion = ease.getInterpolation((elapsed / 340f).coerceIn(0f, 1f))
                fun edge(from: Int, to: Int) = (from + (to - from) * expansion).roundToInt()
                outlineBounds.set(edge(source.left, 0), edge(source.top, 0),
                    edge(source.right, card.width), edge(source.bottom, card.height))
                outlineRadius = sourceRadius + (card.radius - sourceRadius) * expansion
                card.invalidateOutline()
                reveals.forEach { reveal ->
                    val curve = reveal.interpolator ?: ease
                    reveal.view.alpha = curve.getInterpolation(((elapsed - reveal.start) / reveal.duration).coerceIn(0f, 1f))
                }
            }

            frame(0f)
            animation = ValueAnimator.ofFloat(0f, entryDuration).apply {
                duration = entryDuration.toLong()
                interpolator = LinearInterpolator()
                addUpdateListener { frame(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animator: Animator) {
                        if (animation === animator) {
                            animation = null
                            restoreFinalState()
                        }
                    }
                })
                start()
            }
        }
    }

    /** Also removes the pre-draw callback, so leaving early cannot restart a hidden page. */
    fun settle() {
        pendingDraw?.removeListener()
        pendingDraw = null
        val running = animation
        animation = null
        running?.cancel()
        restoreFinalState()
    }

    private fun restoreFinalState() {
        if (outlineOverridden) {
            card.outlineProvider = originalOutline
            card.clipToOutline = originalClip
            card.invalidateOutline()
            outlineOverridden = false
        }
        reveals.forEach { it.view.alpha = 1f }
    }

    private data class Reveal(val view: View, val start: Float, val duration: Float,
        val interpolator: PathInterpolator? = null)
}
