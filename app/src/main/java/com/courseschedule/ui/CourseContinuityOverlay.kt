package com.courseschedule.ui

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import com.courseschedule.data.entity.Course
import com.courseschedule.domain.WeekMotionStyle

/** Keep courses and date columns aligned while a week changes. */
class CourseContinuityOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private var lower: WeekPagerAdapter.WeekViewHolder? = null
    private var upper: WeekPagerAdapter.WeekViewHolder? = null
    private var progress = 0f
    private var sharedCourses: List<CourseContinuityPair> = emptyList()
    private var replacements: List<CourseContinuityPair> = emptyList()
    private var leaving: List<Course> = emptyList()
    private var arriving: List<Course> = emptyList()
    private val origin = IntArray(2)
    private val lowerOrigin = IntArray(2)
    private val upperOrigin = IntArray(2)
    private val pageOrigin = IntArray(2)
    private val scrollOrigin = IntArray(2)
    private val emptyOrigin = IntArray(2)
    private var hiddenEmptyStates = false

    var motionStyle: WeekMotionStyle = WeekMotionStyle.SOFT_SLIDE
        set(value) {
            if (field == value) return
            clear()
            field = value
        }

    internal val isChangingWeeks: Boolean get() = lower != null && upper != null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    internal fun updatePages(
        lower: WeekPagerAdapter.WeekViewHolder?,
        upper: WeekPagerAdapter.WeekViewHolder?,
        progress: Float
    ) {
        // Reattached pages retain usable bounds even when isLaidOut is false.
        // Check the drawing dimensions so recycling never disables the stage.
        if (lower == null || upper == null || progress <= 0f || progress >= 1f ||
            lower.binding.courseTableView.width <= 0 || lower.binding.courseTableView.height <= 0 ||
            upper.binding.courseTableView.width <= 0 || upper.binding.courseTableView.height <= 0) {
            clear()
            return
        }
        if (this.lower !== lower || this.upper !== upper) {
            clear()
            this.lower = lower
            this.upper = upper
            if (motionStyle == WeekMotionStyle.SOFT_SLIDE) {
                lower.resetSelectionMotion()
                upper.resetSelectionMotion()
            }
        }
        val previousProgress = this.progress
        this.progress = progress
        val oldCourses = lower.binding.courseTableView.continuityCourses()
        val newCourses = upper.binding.courseTableView.continuityCourses()
        val pairs = if (motionStyle == WeekMotionStyle.CONTINUITY) CourseContinuity.match(oldCourses, newCourses)
            else emptyList()
        val changed = sharedCourses != pairs || previousProgress != progress
        sharedCourses = pairs
        val unmatchedOld = oldCourses.filter { course -> pairs.none { it.from.id == course.id } }
        val unmatchedNew = newCourses.filter { course -> pairs.none { it.to.id == course.id } }
        val newReplacements = if (motionStyle == WeekMotionStyle.CONTINUITY) unmatchedOld.mapNotNull { course ->
            unmatchedNew.singleOrNull { CourseContinuity.sameSlot(course, it) }
                ?.let { CourseContinuityPair(course, it) }
        } else emptyList()
        val newLeaving = unmatchedOld.filter { course -> newReplacements.none { it.from.id == course.id } }
        val newArriving = unmatchedNew.filter { course -> newReplacements.none { it.to.id == course.id } }
        val sceneChanged = replacements != newReplacements || leaving != newLeaving || arriving != newArriving
        replacements = newReplacements
        leaving = newLeaving
        arriving = newArriving
        // The stage owns every course during a change, including unmatched and inactive cards.
        lower.binding.courseTableView.setContinuityCourses(oldCourses.map { it.id }.toSet(), true)
        upper.binding.courseTableView.setContinuityCourses(newCourses.map { it.id }.toSet(), true)
        lower.binding.weekDayHeader.visibility = INVISIBLE
        upper.binding.weekDayHeader.visibility = INVISIBLE
        hiddenEmptyStates = true
        listOf(lower, upper).forEach { holder ->
            // Start once the illustration is fully entering the visible scene.
            // Starting when an incoming sheet is still outside the clip would
            // consume the main page turn before the user can see it.
            val distance = if (holder === lower) progress else 1f - progress
            holder.binding.emptyCalendar.setStageHost(this, ready = distance <= .25f)
            if (holder.binding.emptyState.visibility == VISIBLE) holder.binding.emptyState.visibility = INVISIBLE
        }
        if (changed || sceneChanged) invalidate()
    }

    internal fun clear() {
        if (hiddenEmptyStates) listOfNotNull(lower, upper).forEach { holder ->
            holder.binding.emptyState.visibility = if (holder.binding.courseTableView.continuityCourses().isEmpty()) VISIBLE else GONE
            holder.binding.emptyCalendar.setStageHost(null)
        }
        hiddenEmptyStates = false
        lower?.binding?.courseTableView?.setContinuityCourses(emptySet(), false)
        upper?.binding?.courseTableView?.setContinuityCourses(emptySet(), false)
        lower?.binding?.weekDayHeader?.visibility = VISIBLE
        upper?.binding?.weekDayHeader?.visibility = VISIBLE
        lower = null
        upper = null
        sharedCourses = emptyList()
        replacements = emptyList()
        leaving = emptyList()
        arriving = emptyList()
        if (progress != 0f) {
            progress = 0f
            invalidate()
        }
    }

    // Positions include each week's saved vertical scroll, but exclude horizontal paging.
    private fun contentOrigin(): Pair<Float, Float>? {
        val source = lower ?: return null
        val destination = upper ?: return null
        getLocationInWindow(origin)
        source.binding.courseTableView.getLocationInWindow(lowerOrigin)
        destination.binding.courseTableView.getLocationInWindow(upperOrigin)
        source.itemView.getLocationInWindow(pageOrigin)
        val x = (lowerOrigin[0] - pageOrigin[0]).toFloat()
        val y = lowerOrigin[1] + (upperOrigin[1] - lowerOrigin[1]) * progress - origin[1]
        return x to y
    }

    internal fun sharedCourseBounds(): Map<Long, RectF> {
        val (x, y) = contentOrigin() ?: return emptyMap()
        return sharedCourses.associate { pair ->
            pair.from.id to localBounds(pair).apply { offset(x, y) }
        }
    }

    private fun localBounds(pair: CourseContinuityPair): RectF {
        val from = lower!!.binding.courseTableView.continuityBounds(pair.from)
        val to = upper!!.binding.courseTableView.continuityBounds(pair.to)
        return RectF(from.left + (to.left - from.left) * progress,
            from.top + (to.top - from.top) * progress,
            from.right + (to.right - from.right) * progress,
            from.bottom + (to.bottom - from.bottom) * progress)
    }

    private fun singleBounds(course: Course, entering: Boolean): RectF {
        val table = (if (entering) upper else lower)!!.binding.courseTableView
        val bounds = table.continuityBounds(course)
        if (!ValueAnimator.areAnimatorsEnabled()) return bounds
        val phase = SchedulePageMotion.phase(progress)
        val remaining = if (entering) 1f - phase else phase
        val scale = 1f - remaining * (1f - if (entering) SchedulePageMotion.ENTER_SCALE else SchedulePageMotion.EXIT_SCALE)
        val offset = resources.displayMetrics.density * remaining *
            if (entering) SchedulePageMotion.ENTER_DP else -SchedulePageMotion.EXIT_DP
        val halfWidth = bounds.width() * scale / 2f
        val halfHeight = bounds.height() * scale / 2f
        return RectF(bounds.centerX() - halfWidth, bounds.centerY() + offset - halfHeight,
            bounds.centerX() + halfWidth, bounds.centerY() + offset + halfHeight)
    }

    internal fun changingCourseBounds(): Map<Long, RectF> {
        val (x, y) = contentOrigin() ?: return emptyMap()
        return (replacements.associate { it.from.id to localBounds(it) } +
            leaving.associate { it.id to singleBounds(it, false) } +
            arriving.associate { it.id to singleBounds(it, true) })
            .mapValues { (_, bounds) -> bounds.apply { offset(x, y) } }
    }

    private fun drawSingleCourse(canvas: Canvas, course: Course, entering: Boolean, opacity: Float) {
        val table = (if (entering) upper else lower)!!.binding.courseTableView
        val original = table.continuityBounds(course)
        val visual = singleBounds(course, entering)
        val save = canvas.save()
        canvas.translate(visual.left, visual.top)
        canvas.scale(visual.width() / original.width(), visual.height() / original.height())
        canvas.translate(-original.left, -original.top)
        table.drawContinuityCourse(canvas, course, original, opacity)
        canvas.restoreToCount(save)
    }

    private fun drawHeaderLine(canvas: Canvas, view: TextView, line: Int, opacity: Float) {
        val text = view.layout ?: return
        if (line >= text.lineCount || opacity <= 0f) return
        val save = canvas.saveLayerAlpha(view.left.toFloat(), view.top.toFloat(), view.right.toFloat(),
            view.bottom.toFloat(), (255 * opacity).toInt())
        canvas.translate((view.left + view.totalPaddingLeft).toFloat(), (view.top + view.totalPaddingTop).toFloat())
        canvas.clipRect(0f, text.getLineTop(line).toFloat(), text.width.toFloat(), text.getLineBottom(line).toFloat())
        val color = text.paint.color
        text.paint.color = view.currentTextColor
        text.draw(canvas)
        text.paint.color = color
        canvas.restoreToCount(save)
    }

    private fun drawHeaderBackground(canvas: Canvas, view: TextView, opacity: Float) {
        val background = view.background ?: return
        val save = canvas.saveLayerAlpha(view.left.toFloat(), view.top.toFloat(), view.right.toFloat(),
            view.bottom.toFloat(), (255 * opacity).toInt())
        canvas.translate(view.left.toFloat(), view.top.toFloat())
        background.setBounds(0, 0, view.width, view.height)
        background.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawDateHeader(canvas: Canvas, phase: Float) {
        val old = lower!!.binding.weekDayHeader
        val new = upper!!.binding.weekDayHeader
        val oldNumber = SchedulePageMotion.outgoingText(progress)
        val newNumber = SchedulePageMotion.incomingText(progress)
        for (index in 0 until old.childCount) {
            val from = old.getChildAt(index) as? TextView ?: continue
            val to = new.getChildAt(index) as? TextView ?: continue
            if (from.visibility == GONE || to.visibility == GONE) continue
            drawHeaderBackground(canvas, from, 1f - phase)
            drawHeaderBackground(canvas, to, phase)
            val stableLine = if (index == 0) 1 else 0
            val numberLine = if (index == 0) 0 else 1
            if (from.currentTextColor == to.currentTextColor && from.typeface == to.typeface) {
                drawHeaderLine(canvas, from, stableLine, 1f)
            } else {
                drawHeaderLine(canvas, from, stableLine, 1f - phase)
                drawHeaderLine(canvas, to, stableLine, phase)
            }
            // Fade old numbers completely before showing new ones, avoiding doubled digits.
            drawHeaderLine(canvas, from, numberLine, oldNumber)
            drawHeaderLine(canvas, to, numberLine, newNumber)
        }
    }

    private fun sceneFrame(incoming: Boolean): WeekSlideMotion.Frame {
        val source = lower!!
        return WeekSlideMotion.frame(if (incoming) 1f - progress else -progress,
            width - source.binding.tvMonthLabel.width.toFloat(), resources.displayMetrics.density,
            ValueAnimator.areAnimatorsEnabled())
    }

    private fun tableOrigin(holder: WeekPagerAdapter.WeekViewHolder): Pair<Float, Float> {
        getLocationInWindow(origin)
        holder.binding.courseTableView.getLocationInWindow(lowerOrigin)
        holder.itemView.getLocationInWindow(pageOrigin)
        val x = (lowerOrigin[0] - pageOrigin[0]).toFloat()
        // Both sheets use the same intermediate vertical position as the time
        // axis, even when the two weeks have different saved scroll offsets.
        return x to contentOrigin()!!.second
    }

    internal fun slidingCourseBounds(incoming: Boolean): Map<Long, RectF> {
        if (!isChangingWeeks || motionStyle != WeekMotionStyle.SOFT_SLIDE) return emptyMap()
        val holder = (if (incoming) upper else lower)!!
        val (x, y) = tableOrigin(holder)
        val frame = sceneFrame(incoming)
        val pivotX = (width + holder.binding.tvMonthLabel.width) / 2f
        val pivotY = holder.binding.weekDayHeader.height.toFloat()
        return holder.binding.courseTableView.continuityCourses().associate { course ->
            val bounds = holder.binding.courseTableView.continuityBounds(course).apply { offset(x, y) }
            course.id to RectF(pivotX + (bounds.left - pivotX) * frame.scale + frame.offsetX,
                pivotY + (bounds.top - pivotY) * frame.scale,
                pivotX + (bounds.right - pivotX) * frame.scale + frame.offsetX,
                pivotY + (bounds.bottom - pivotY) * frame.scale)
        }
    }

    private fun transformScene(canvas: Canvas, frame: WeekSlideMotion.Frame, holder: WeekPagerAdapter.WeekViewHolder) {
        canvas.translate(frame.offsetX, 0f)
        canvas.scale(frame.scale, frame.scale, (width + holder.binding.tvMonthLabel.width) / 2f,
            holder.binding.weekDayHeader.height.toFloat())
    }

    private fun drawSlidingScene(canvas: Canvas, holder: WeekPagerAdapter.WeekViewHolder, incoming: Boolean) {
        val frame = sceneFrame(incoming)
        val gridLeft = holder.binding.tvMonthLabel.width.toFloat()
        val header = holder.binding.weekDayHeader
        val headerSave = canvas.save()
        canvas.clipRect(gridLeft, 0f, width.toFloat(), header.height.toFloat())
        transformScene(canvas, frame, holder)
        for (index in 1 until header.childCount) {
            val day = header.getChildAt(index) as? TextView ?: continue
            if (day.visibility == GONE) continue
            drawHeaderBackground(canvas, day, frame.opacity)
            drawHeaderLine(canvas, day, 0, frame.opacity)
            drawHeaderLine(canvas, day, 1, frame.opacity)
        }
        canvas.restoreToCount(headerSave)

        holder.binding.scheduleScroll.getLocationInWindow(scrollOrigin)
        getLocationInWindow(origin)
        val top = (scrollOrigin[1] - origin[1]).toFloat()
        val contentSave = canvas.save()
        canvas.clipRect(gridLeft, top, width.toFloat(), top + holder.binding.scheduleScroll.height)
        transformScene(canvas, frame, holder)
        val (x, y) = tableOrigin(holder)
        val tableSave = canvas.save()
        canvas.translate(x, y)
        val table = holder.binding.courseTableView
        table.continuityCourses().forEach { course ->
            table.drawContinuityCourse(canvas, course, table.continuityBounds(course), frame.opacity)
        }
        canvas.restoreToCount(tableSave)
        canvas.restoreToCount(contentSave)
    }

    private fun drawEmptyContent(canvas: Canvas, holder: WeekPagerAdapter.WeekViewHolder,
        frame: EmptyWeekMotion.Frame) {
        if (frame.opacity <= 0f) return
        holder.binding.emptyState.getLocationInWindow(emptyOrigin)
        holder.itemView.getLocationInWindow(pageOrigin)
        val empty = holder.binding.emptyState
        val save = canvas.save()
        canvas.translate(emptyOrigin[0] - pageOrigin[0] + frame.offsetX,
            emptyOrigin[1] - origin[1] + frame.offsetY)
        canvas.scale(frame.scale, frame.scale, empty.width / 2f, empty.height * .35f)
        val emptySave = canvas.saveLayerAlpha(0f, 0f, empty.width.toFloat(),
            empty.height.toFloat(), (255 * frame.opacity.coerceIn(0f, 1f)).toInt())
        empty.draw(canvas)
        canvas.restoreToCount(emptySave)
        canvas.restoreToCount(save)
    }

    private fun drawEmptyTransition(canvas: Canvas) {
        val source = lower!!
        val destination = upper!!
        val sourceEmpty = source.binding.courseTableView.continuityCourses().isEmpty()
        val destinationEmpty = destination.binding.courseTableView.continuityCourses().isEmpty()
        if (!sourceEmpty && !destinationEmpty) return

        getLocationInWindow(origin)
        source.binding.scheduleScroll.getLocationInWindow(scrollOrigin)
        val top = (scrollOrigin[1] - origin[1]).toFloat()
        val save = canvas.save()
        canvas.clipRect(source.binding.tvMonthLabel.width.toFloat(), top, width.toFloat(),
            top + source.binding.scheduleScroll.height)
        val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
        if (sourceEmpty && destinationEmpty && !animationsEnabled) {
            drawEmptyContent(canvas, source, EmptyWeekMotion.Frame(0f, 0f, 1f, 1f))
        } else {
            // Empty weeks have their own identities too: render both sides of
            // the change instead of pinning one identical placeholder at rest.
            listOf(false, true).forEach { incoming ->
                if (if (incoming) destinationEmpty else sourceEmpty) {
                    val holder = if (incoming) destination else source
                    val contentSave = canvas.save()
                    val softSlide = motionStyle == WeekMotionStyle.SOFT_SLIDE
                    val frame = EmptyWeekMotion.frame(if (incoming) 1f - progress else -progress,
                        resources.displayMetrics.density, softSlide, animationsEnabled)
                    if (softSlide) transformScene(canvas, sceneFrame(incoming), holder)
                    drawEmptyContent(canvas, holder, frame)
                    canvas.restoreToCount(contentSave)
                }
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawSoftSlide(canvas: Canvas) {
        val source = lower!!
        val fromMonth = source.binding.tvMonthLabel
        val toMonth = upper!!.binding.tvMonthLabel
        drawHeaderLine(canvas, fromMonth, 1, 1f)
        if (fromMonth.text.toString() == toMonth.text.toString()) drawHeaderLine(canvas, fromMonth, 0, 1f)
        else {
            drawHeaderLine(canvas, fromMonth, 0, SchedulePageMotion.outgoingText(progress))
            drawHeaderLine(canvas, toMonth, 0, SchedulePageMotion.incomingText(progress))
        }
        val (x, y) = contentOrigin() ?: return
        getLocationInWindow(origin)
        source.binding.scheduleScroll.getLocationInWindow(scrollOrigin)
        val top = (scrollOrigin[1] - origin[1]).toFloat()
        val axisSave = canvas.save()
        canvas.clipRect(0f, top, fromMonth.width.toFloat(), top + source.binding.scheduleScroll.height)
        canvas.translate(x, y)
        source.binding.courseTableView.drawContinuityTimeColumn(canvas)
        canvas.restoreToCount(axisSave)
        drawSlidingScene(canvas, source, false)
        drawSlidingScene(canvas, upper!!, true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = lower ?: return
        if (motionStyle == WeekMotionStyle.SOFT_SLIDE) {
            drawSoftSlide(canvas)
            drawEmptyTransition(canvas)
            return
        }
        val (x, y) = contentOrigin() ?: return
        val phase = SchedulePageMotion.phase(progress)
        // Keep weekdays aligned with their course columns while only the dates change.
        drawDateHeader(canvas, phase)
        source.binding.scheduleScroll.getLocationInWindow(scrollOrigin)
        val top = (scrollOrigin[1] - origin[1]).toFloat()
        val save = canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), top + source.binding.scheduleScroll.height)
        canvas.translate(x, y)
        source.binding.courseTableView.drawContinuityTimeColumn(canvas)
        val destination = upper!!.binding.courseTableView
        leaving.forEach { course ->
            drawSingleCourse(canvas, course, false, 1f - phase)
        }
        arriving.forEach { course ->
            drawSingleCourse(canvas, course, true, phase)
        }
        replacements.forEach { pair ->
            source.binding.courseTableView.drawTransitionCourse(canvas, pair.from, destination, pair.to, localBounds(pair), progress)
        }
        sharedCourses.forEach { pair ->
            source.binding.courseTableView.drawTransitionCourse(canvas, pair.from, destination, pair.to, localBounds(pair), progress)
        }
        canvas.restoreToCount(save)
        drawEmptyTransition(canvas)
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }
}
