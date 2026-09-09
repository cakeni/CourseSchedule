package com.courseschedule.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.utils.SchedulePreferences
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Weekly timetable canvas. Dimensions are density-aware so the grid stays
 * readable on both phones and emulators.
 */
class CourseTableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TOTAL_SECTIONS = 12
        private const val PRESS_IN_DURATION_MS = 88L
        private const val PRESS_OUT_DURATION_MS = 165L
        private const val PRESSED_SCALE_X = 0.97f
        private const val PRESSED_SCALE_Y = 0.985f
        private val PRESS_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
    }

    private val density = resources.displayMetrics.density
    private var sectionHeight = dp(64f)
    private val timeColumnWidth = dp(48f)
    private val courseInset = dp(3f)
    private val courseCornerRadius = dp(10f)

    private val courseColors = intArrayOf(
        R.color.course_red,
        R.color.course_pink,
        R.color.course_purple,
        R.color.course_deep_purple,
        R.color.course_indicate,
        R.color.course_blue,
        R.color.course_light_blue,
        R.color.course_cyan,
        R.color.course_teal,
        R.color.course_green,
        R.color.course_light_green,
        R.color.course_lime,
        R.color.course_yellow,
        R.color.course_amber,
        R.color.course_orange,
        R.color.course_brown
    ).map { ContextCompat.getColor(context, it) }.toIntArray()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_variant)
        alpha = 105
        style = Paint.Style.FILL
    }

    private val todayColumnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.today_column)
        style = Paint.Style.FILL
    }

    private val afternoonBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.schedule_afternoon)
        style = Paint.Style.FILL
    }

    private val eveningBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.schedule_evening)
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.grid_line)
        strokeWidth = dp(0.5f)
        style = Paint.Style.STROKE
    }

    private val groupDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider)
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
    }

    private val coursePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val courseStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 190
        strokeWidth = dp(1.25f)
        style = Paint.Style.STROKE
    }

    private val courseNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.on_primary)
        textSize = sp(12f)
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        )
    }

    private val courseRoomPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.on_primary)
        alpha = 220
        textSize = sp(10f)
    }

    private val courseTeacherPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.on_primary)
        alpha = 205
        textSize = sp(9f)
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_tertiary)
        textSize = sp(9.5f)
        textAlign = Paint.Align.CENTER
    }

    private val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        )
    }

    private var courses: List<Course> = emptyList()
    private var currentWeek = 1
    private var visibleDaysCount = 7
    private var showTimes = true
    private var highlightedDay: Int? = null
    private var onCourseClickListener: ((Course, View, RectF) -> Unit)? = null
    private var onEmptySlotClickListener: ((dayOfWeek: Int, section: Int) -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pressedCourse: Course? = null
    private val coursePressMotion = CoursePressMotion()
    private var pagerOffset = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchMoved = false
    private var pressPivotX = 0f
    private var pressPivotY = 0f
    private var visibleCourseCache: List<Course> = emptyList()
    private val courseBoundsCache = mutableMapOf<Course, RectF>()
    private val courseTextLayoutCache = mutableMapOf<CourseTextLayoutKey, CourseTextLayout>()
    private val cardDrawBounds = RectF()

    private var dayWidth = 0f
    private var totalWidth = 0f
    private var totalHeight = 0f

    private var sectionTimes = SchedulePreferences.DEFAULT_SECTION_TIMES

    private data class CourseTextLayoutKey(
        val course: Course,
        val isCurrentWeek: Boolean
    )

    private data class CourseTextLayout(
        val name: StaticLayout,
        val room: StaticLayout?,
        val teacher: StaticLayout?,
        val nameGap: Float,
        val detailGap: Float,
        val contentHeight: Float
    )

    private inner class CoursePressMotion {
        var progress = 0f
            private set
        private var animator: ValueAnimator? = null
        private var generation = 0

        fun animateTo(target: Float, duration: Long, onEnd: () -> Unit = {}) {
            val currentGeneration = ++generation
            animator?.cancel()
            if (abs(progress - target) < 0.001f) {
                progress = target
                onEnd()
                invalidate()
                return
            }
            animator = ValueAnimator.ofFloat(progress, target).apply {
                this.duration = duration
                interpolator = PRESS_INTERPOLATOR
                addUpdateListener { valueAnimator ->
                    progress = valueAnimator.animatedValue as Float
                    ViewCompat.postInvalidateOnAnimation(this@CourseTableView)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (generation != currentGeneration) return
                        progress = target
                        animator = null
                        onEnd()
                        invalidate()
                    }
                })
                start()
            }
        }

        fun reset() {
            generation++
            animator?.cancel()
            animator = null
            progress = 0f
            invalidate()
        }
    }

    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            return visibleCourses().indexOfFirst { course -> courseBounds(course).contains(x, y) }
                .takeIf { it >= 0 }
                ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            visibleCourses().indices.forEach(virtualViewIds::add)
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            val course = visibleCourses().getOrNull(virtualViewId) ?: return
            val bounds = courseBounds(course)
            node.setBoundsInParent(
                Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
            )
            node.className = android.widget.Button::class.java.name
            node.contentDescription = courseDescription(course)
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: android.os.Bundle?
        ): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val course = visibleCourses().getOrNull(virtualViewId) ?: return false
            onCourseClickListener?.invoke(course, this@CourseTableView, RectF(courseBounds(course)))
            sendEventForVirtualView(virtualViewId, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = dp(360f).roundToInt()
        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        val measuredDayWidth = (measuredWidth - timeColumnWidth) / visibleDaysCount
        if (abs(dayWidth - measuredDayWidth) > 0.5f) {
            courseBoundsCache.clear()
            courseTextLayoutCache.clear()
        }
        dayWidth = measuredDayWidth
        totalWidth = measuredWidth.toFloat()
        totalHeight = TOTAL_SECTIONS * sectionHeight
        setMeasuredDimension(measuredWidth, totalHeight.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(
            timeColumnWidth,
            sectionHeight * 4,
            totalWidth,
            sectionHeight * 8,
            afternoonBandPaint
        )
        canvas.drawRect(
            timeColumnWidth,
            sectionHeight * 8,
            totalWidth,
            totalHeight,
            eveningBandPaint
        )
        canvas.drawRect(0f, 0f, timeColumnWidth, totalHeight, backgroundPaint)
        drawHighlightedDay(canvas)
        drawGrid(canvas)
        drawTimeColumn(canvas)
        drawCourses(canvas)
    }

    private fun drawHighlightedDay(canvas: Canvas) {
        val day = highlightedDay ?: return
        if (day !in 1..visibleDaysCount) return
        val left = timeColumnWidth + (day - 1) * dayWidth
        canvas.drawRect(left, 0f, left + dayWidth, totalHeight, todayColumnPaint)
    }

    private fun drawTimeColumn(canvas: Canvas) {
        for (section in 0 until TOTAL_SECTIONS) {
            val top = section * sectionHeight
            if (showTimes) {
                canvas.drawText(
                    (section + 1).toString(),
                    timeColumnWidth / 2f,
                    top + sectionHeight / 2f - dp(5f),
                    sectionPaint
                )
                canvas.drawText(
                    sectionTimes[section],
                    timeColumnWidth / 2f,
                    top + sectionHeight / 2f + dp(14f),
                    timePaint
                )
            } else {
                val baseline = top + sectionHeight / 2f -
                    (sectionPaint.descent() + sectionPaint.ascent()) / 2f
                canvas.drawText(
                    (section + 1).toString(),
                    timeColumnWidth / 2f,
                    baseline,
                    sectionPaint
                )
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        for (section in 0..TOTAL_SECTIONS) {
            val y = section * sectionHeight
            val paint = if (section == 4 || section == 8) groupDividerPaint else gridPaint
            canvas.drawLine(timeColumnWidth, y, totalWidth, y, paint)
        }

        canvas.drawLine(timeColumnWidth, 0f, timeColumnWidth, totalHeight, groupDividerPaint)
        for (day in 1 until visibleDaysCount) {
            val x = timeColumnWidth + day * dayWidth
            canvas.drawLine(x, 0f, x, totalHeight, gridPaint)
        }
    }

    private fun drawCourses(canvas: Canvas) {
        val visibleCourses = visibleCourses()
        visibleCourses.forEach { course ->
            val bounds = courseBounds(course)
            val isCurrentWeek = ScheduleRules.isCourseInWeek(course, currentWeek)
            val sectionSpan = (course.endSection - course.startSection + 1).coerceAtLeast(1)
            val motionVariant = Math.floorMod(
                course.dayOfWeek * 7 + course.startSection * 11 + course.endSection * 3,
                5
            )
            val arcDirection = if ((course.dayOfWeek + course.startSection) % 2 == 0) -1f else 1f
            val motionDistance = abs(pagerOffset)
            val travelDistance = dp(
                8f + motionVariant * 2.2f + sectionSpan.coerceAtMost(4) * 0.7f
            )
            val saveCount = canvas.save()

            canvas.translate(
                -pagerOffset * travelDistance,
                sin(motionDistance * Math.PI).toFloat() *
                    arcDirection * dp(0.8f + motionVariant * 0.32f)
            )

            canvas.rotate(
                -pagerOffset * arcDirection * (0.25f + motionVariant * 0.1f),
                bounds.centerX(),
                bounds.centerY()
            )

            val motionScale = 1f - motionDistance *
                (0.006f + motionVariant * 0.0015f + sectionSpan.coerceAtMost(4) * 0.001f)
            canvas.scale(
                motionScale,
                motionScale,
                bounds.centerX(),
                bounds.centerY()
            )

            val pressProgress = if (course == pressedCourse) coursePressMotion.progress else 0f
            val motionAlpha = (255f * (1f - motionDistance * (0.08f + motionVariant * 0.008f)))
                .roundToInt()
                .coerceIn(0, 255)
            val alpha = if (isCurrentWeek) motionAlpha else (motionAlpha * 0.46f).roundToInt()
            drawCourse(canvas, course, alpha, isCurrentWeek, pressProgress)
            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawCourse(
        canvas: Canvas,
        course: Course,
        alpha: Int,
        isCurrentWeek: Boolean,
        pressProgress: Float
    ) {
        val bounds = courseBounds(course)
        val left = bounds.left
        val top = bounds.top
        val right = bounds.right
        val bottom = bounds.bottom
        if (right <= left || bottom <= top) return

        val baseColor = courseColors[Math.floorMod(course.colorIndex, courseColors.size)]
        coursePaint.color = ColorUtils.blendARGB(baseColor, Color.BLACK, 0.08f * pressProgress)
        coursePaint.alpha = alpha
        courseStrokePaint.alpha = ((190f + 40f * pressProgress) * alpha / 255f)
            .roundToInt()
        courseStrokePaint.strokeWidth = dp(1.25f + 0.4f * pressProgress)
        val pressScaleX = 1f - (1f - PRESSED_SCALE_X) * pressProgress
        val pressScaleY = 1f - (1f - PRESSED_SCALE_Y) * pressProgress
        val pivotX = pressPivotX.coerceIn(left, right)
        val pivotY = pressPivotY.coerceIn(top, bottom)
        val pressTranslationY = dp(1.4f) * pressProgress
        cardDrawBounds.set(
            pivotX + (left - pivotX) * pressScaleX,
            pivotY + (top - pivotY) * pressScaleY + pressTranslationY,
            pivotX + (right - pivotX) * pressScaleX,
            pivotY + (bottom - pivotY) * pressScaleY + pressTranslationY
        )
        canvas.drawRoundRect(
            cardDrawBounds,
            courseCornerRadius,
            courseCornerRadius,
            coursePaint
        )
        canvas.drawRoundRect(
            cardDrawBounds,
            courseCornerRadius,
            courseCornerRadius,
            courseStrokePaint
        )

        val textLayout = courseTextLayoutCache.getOrPut(
            CourseTextLayoutKey(course, isCurrentWeek)
        ) {
            buildCourseTextLayout(course, isCurrentWeek, bounds)
        }
        textLayout.name.paint.alpha = alpha
        textLayout.room?.paint?.alpha = 220 * alpha / 255
        textLayout.teacher?.paint?.alpha = 205 * alpha / 255
        val horizontalPadding = dp(6f)
        val verticalPadding = dp(5f)
        var textTop = top + ((bottom - top - textLayout.contentHeight) / 2f)
            .coerceAtLeast(verticalPadding)
        val normalizedTouchX = ((pressPivotX - bounds.centerX()) / bounds.width())
            .coerceIn(-0.5f, 0.5f)
        val textTranslationX = dp(0.7f) * normalizedTouchX * pressProgress
        val textTranslationY = dp(0.7f) * pressProgress

        canvas.save()
        canvas.translate(
            left + horizontalPadding + textTranslationX,
            textTop + textTranslationY
        )
        textLayout.name.draw(canvas)
        canvas.restore()

        textTop += textLayout.name.height + textLayout.nameGap
        textLayout.room?.let { roomLayout ->
            canvas.save()
            canvas.translate(
                left + horizontalPadding + textTranslationX,
                textTop + textTranslationY
            )
            roomLayout.draw(canvas)
            canvas.restore()
            textTop += roomLayout.height
            if (textLayout.teacher != null) textTop += textLayout.detailGap
        }
        textLayout.teacher?.let { teacherLayout ->
            canvas.save()
            canvas.translate(
                left + horizontalPadding + textTranslationX,
                textTop + textTranslationY
            )
            teacherLayout.draw(canvas)
            canvas.restore()
        }
    }

    private fun buildCourseTextLayout(
        course: Course,
        isCurrentWeek: Boolean,
        bounds: RectF
    ): CourseTextLayout {
        val courseName = displayCourseName(course.courseName)
        val displayName = if (isCurrentWeek) {
            courseName
        } else {
            resources.getString(R.string.inactive_course_name_format, courseName)
        }
        val namePaint = TextPaint(courseNamePaint).apply {
            alpha = 255
            textSize = sp(
                when {
                    displayName.count { !it.isWhitespace() } >= 18 -> 9f
                    displayName.count { !it.isWhitespace() } >= 12 -> 10f
                    else -> 11.5f
                }
            )
        }
        val roomPaint = TextPaint(courseRoomPaint).apply {
            alpha = 255
            textSize = sp(9f)
        }
        val teacherPaint = TextPaint(courseTeacherPaint).apply {
            alpha = 255
            textSize = sp(8.5f)
        }
        val horizontalPadding = dp(6f)
        val textWidth = (bounds.width() - horizontalPadding * 2).roundToInt().coerceAtLeast(1)
        val showDetails = course.endSection - course.startSection + 1 >= 2
        val roomLayout = if (showDetails && course.classroom.isNotBlank()) {
            buildTextLayout("@${course.classroom}", roomPaint, textWidth, 2)
        } else {
            null
        }
        val teacherLayout = if (showDetails && course.teacher.isNotBlank()) {
            buildTextLayout(course.teacher, teacherPaint, textWidth, 1)
        } else {
            null
        }
        val detailCount = listOfNotNull(roomLayout, teacherLayout).size
        val nameGap = if (detailCount > 0) dp(3f) else 0f
        val detailGap = if (detailCount > 1) dp(1f) else 0f
        val detailsHeight = (roomLayout?.height ?: 0) + (teacherLayout?.height ?: 0) + detailGap
        val nameLineHeight = namePaint.fontMetrics.run { descent - ascent }
        val availableNameHeight = bounds.height() - dp(10f) - detailsHeight - nameGap
        val nameLines = (availableNameHeight / nameLineHeight).toInt().coerceIn(1, 7)
        val nameLayout = buildTextLayout(displayName, namePaint, textWidth, nameLines)
        return CourseTextLayout(
            name = nameLayout,
            room = roomLayout,
            teacher = teacherLayout,
            nameGap = nameGap,
            detailGap = detailGap,
            contentHeight = nameLayout.height + detailsHeight + nameGap
        )
    }

    private fun buildTextLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int
    ): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setIncludePad(false)
        .setLineSpacing(0f, 1f)
        .setMaxLines(maxLines)
        .build()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetCoursePress()
                touchDownX = event.x
                touchDownY = event.y
                touchMoved = false
                pressedCourse = courseAt(event.x, event.y)
                if (pressedCourse != null) {
                    pressPivotX = event.x
                    pressPivotY = event.y
                    coursePressMotion.animateTo(1f, PRESS_IN_DURATION_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val course = pressedCourse
                val movedPastSlop = abs(event.x - touchDownX) > touchSlop ||
                    abs(event.y - touchDownY) > touchSlop
                val bounds = course?.let(::courseBounds)
                val outsideCourse = bounds != null && (
                    event.x < bounds.left - touchSlop ||
                        event.x > bounds.right + touchSlop ||
                        event.y < bounds.top - touchSlop ||
                        event.y > bounds.bottom + touchSlop
                    )
                if (!touchMoved && movedPastSlop) touchMoved = true
                if (course != null && (movedPastSlop || outsideCourse)) {
                    touchMoved = true
                    releaseCoursePress()
                }
            }
            MotionEvent.ACTION_UP -> {
                val course = pressedCourse
                val isClick = !touchMoved && course != null && courseBounds(course).contains(
                    event.x,
                    event.y
                )
                releaseCoursePress()
                if (isClick && course != null) {
                    val sourceBounds = RectF(courseBounds(course))
                    performClick()
                    onCourseClickListener?.invoke(course, this, sourceBounds)
                } else if (!touchMoved && course == null) {
                    emptySlotAt(event.x, event.y)?.let { (day, section) ->
                        performClick()
                        onEmptySlotClickListener?.invoke(day, section)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!touchMoved) {
                    touchMoved = true
                    releaseCoursePress()
                }
            }
        }
        return true
    }

    private fun courseAt(x: Float, y: Float): Course? {
        if (x <= timeColumnWidth) return null
        return visibleCourses().firstOrNull { course ->
            courseBounds(course).contains(x, y)
        }
    }

    private fun emptySlotAt(x: Float, y: Float): Pair<Int, Int>? {
        if (x <= timeColumnWidth || x >= totalWidth || y < 0f || y >= totalHeight || dayWidth <= 0f) {
            return null
        }
        val day = ((x - timeColumnWidth) / dayWidth).toInt() + 1
        val section = (y / sectionHeight).toInt() + 1
        return (day to section).takeIf { day in 1..visibleDaysCount && section in 1..TOTAL_SECTIONS }
    }

    private fun resetCoursePress() {
        coursePressMotion.reset()
        pressedCourse = null
    }

    private fun releaseCoursePress() {
        val course = pressedCourse ?: return
        coursePressMotion.animateTo(0f, PRESS_OUT_DURATION_MS) {
            if (pressedCourse == course) pressedCourse = null
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        return accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    }

    override fun onDetachedFromWindow() {
        resetCoursePress()
        super.onDetachedFromWindow()
    }

    fun setCourses(courses: List<Course>) {
        resetCoursePress()
        this.courses = courses
        rebuildVisibleCourses()
        clearCourseRenderCaches()
        accessibilityHelper.invalidateRoot()
        invalidate()
    }

    fun setPagerOffset(position: Float) {
        val newOffset = position.coerceIn(-1f, 1f)
        if (abs(pagerOffset - newOffset) < 0.001f) return
        pagerOffset = newOffset
        ViewCompat.postInvalidateOnAnimation(this)
    }

    fun resetPagerMotion() {
        pagerOffset = 0f
        invalidate()
    }

    fun setCurrentWeek(week: Int) {
        if (currentWeek == week) return
        currentWeek = week
        rebuildVisibleCourses()
        courseTextLayoutCache.clear()
        accessibilityHelper.invalidateRoot()
        invalidate()
    }

    fun setHighlightedDay(day: Int?) {
        highlightedDay = day
        invalidate()
    }

    fun applyDisplaySettings(
        showWeekend: Boolean,
        showTimes: Boolean,
        sectionHeightDp: Int,
        sectionTimes: List<String>
    ) {
        visibleDaysCount = if (showWeekend) 7 else 5
        this.showTimes = showTimes
        this.sectionTimes = sectionTimes.takeIf { it.size == TOTAL_SECTIONS }
            ?: SchedulePreferences.DEFAULT_SECTION_TIMES
        sectionHeight = dp(sectionHeightDp.coerceIn(56, 104).toFloat())
        rebuildVisibleCourses()
        clearCourseRenderCaches()
        accessibilityHelper.invalidateRoot()
        requestLayout()
        invalidate()
    }

    fun setOnCourseClickListener(listener: (Course, View, RectF) -> Unit) {
        onCourseClickListener = listener
    }

    fun setOnEmptySlotClickListener(listener: (dayOfWeek: Int, section: Int) -> Unit) {
        onEmptySlotClickListener = listener
    }

    private fun rebuildVisibleCourses() {
        visibleCourseCache = courses.filter { it.dayOfWeek <= visibleDaysCount }
    }

    private fun clearCourseRenderCaches() {
        courseBoundsCache.clear()
        courseTextLayoutCache.clear()
    }

    private fun visibleCourses(): List<Course> = visibleCourseCache

    private fun courseBounds(course: Course): RectF = courseBoundsCache.getOrPut(course) {
        RectF(
            timeColumnWidth + (course.dayOfWeek - 1) * dayWidth + courseInset,
            (course.startSection - 1) * sectionHeight + courseInset,
            timeColumnWidth + course.dayOfWeek * dayWidth - courseInset,
            course.endSection * sectionHeight - courseInset
        )
    }

    private fun courseDescription(course: Course): String {
        val days = resources.getStringArray(R.array.weekdays)
        val weekType = when (course.weekType) {
            1 -> resources.getString(R.string.odd_week)
            2 -> resources.getString(R.string.even_week)
            else -> resources.getString(R.string.every_week)
        }
        val description = resources.getString(
            R.string.course_accessibility_description,
            displayCourseName(course.courseName),
            days.getOrElse(course.dayOfWeek - 1) { "" },
            course.startSection,
            course.endSection,
            course.startWeek,
            course.endWeek,
            weekType,
            course.teacher.ifBlank { resources.getString(R.string.not_set) },
            course.classroom.ifBlank { resources.getString(R.string.not_set) }
        )
        return if (ScheduleRules.isCourseInWeek(course, currentWeek)) {
            description
        } else {
            "${resources.getString(R.string.inactive_course_label)}，$description"
        }
    }

    private fun dp(value: Float): Float = value * density

    private fun displayCourseName(value: String): String {
        val cleaned = value
            .replaceFirst(Regex("^\\s*\\d{6,}\\s*-\\s*"), "")
            .replace(Regex("\\s*\\[[^]]+]\\s*$"), "")
            .trim()
        return cleaned.ifBlank { value }
    }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )
}
