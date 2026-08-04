package com.courseschedule.view

import android.content.Context
import android.graphics.Canvas
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.utils.SchedulePreferences
import kotlin.math.roundToInt

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
    }

    private val density = resources.displayMetrics.density
    private var sectionHeight = dp(64f)
    private val timeColumnWidth = dp(48f)
    private val courseInset = dp(2.5f)
    private val courseCornerRadius = dp(6f)

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
    private var onCourseClickListener: ((Course) -> Unit)? = null

    private var dayWidth = 0f
    private var totalWidth = 0f
    private var totalHeight = 0f

    private var sectionTimes = SchedulePreferences.DEFAULT_SECTION_TIMES

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
            onCourseClickListener?.invoke(course)
            sendEventForVirtualView(virtualViewId, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = dp(360f).roundToInt()
        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        dayWidth = (measuredWidth - timeColumnWidth) / visibleDaysCount
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
        visibleCourses().forEach { course -> drawCourse(canvas, course) }
    }

    private fun drawCourse(canvas: Canvas, course: Course) {
        val bounds = courseBounds(course)
        val left = bounds.left
        val top = bounds.top
        val right = bounds.right
        val bottom = bounds.bottom
        if (right <= left || bottom <= top) return

        coursePaint.color = courseColors[Math.floorMod(course.colorIndex, courseColors.size)]
        canvas.drawRoundRect(
            RectF(left, top, right, bottom),
            courseCornerRadius,
            courseCornerRadius,
            coursePaint
        )

        val displayName = displayCourseName(course.courseName)
        val nameLength = displayName.count { !it.isWhitespace() }
        courseNamePaint.textSize = sp(
            when {
                nameLength >= 18 -> 8.5f
                nameLength >= 12 -> 9.5f
                else -> 11f
            }
        )
        courseRoomPaint.textSize = sp(8.5f)
        courseTeacherPaint.textSize = sp(8f)

        val horizontalPadding = dp(3f)
        val textWidth = (right - left - horizontalPadding * 2).roundToInt().coerceAtLeast(1)
        val sectionCount = course.endSection - course.startSection + 1
        val showDetails = sectionCount >= 2
        val teacherLayout = if (showDetails && course.teacher.isNotBlank()) {
            buildTextLayout(
                resources.getString(R.string.course_teacher_inline, course.teacher),
                courseTeacherPaint,
                textWidth,
                1
            )
        } else {
            null
        }
        val roomLayout = if (showDetails && course.classroom.isNotBlank()) {
            buildTextLayout(course.classroom, courseRoomPaint, textWidth, 2)
        } else {
            null
        }
        val detailLayouts = listOfNotNull(teacherLayout, roomLayout)
        val verticalPadding = dp(5f)
        val nameGap = if (detailLayouts.isNotEmpty()) dp(3f) else 0f
        val detailGap = if (detailLayouts.size > 1) dp(1f) else 0f
        val detailsHeight = detailLayouts.sumOf { it.height } + detailGap
        val nameLineHeight = courseNamePaint.fontMetrics.run { descent - ascent }
        val availableNameHeight = bottom - top - verticalPadding * 2 -
            detailsHeight - nameGap
        val nameLines = (availableNameHeight / nameLineHeight)
            .toInt()
            .coerceIn(1, 7)
        val nameLayout = buildTextLayout(displayName, courseNamePaint, textWidth, nameLines)

        val contentHeight = nameLayout.height + detailsHeight + nameGap
        var textTop = top + ((bottom - top - contentHeight) / 2f).coerceAtLeast(verticalPadding)

        canvas.save()
        canvas.translate(left + horizontalPadding, textTop)
        nameLayout.draw(canvas)
        canvas.restore()

        textTop += nameLayout.height + nameGap
        detailLayouts.forEachIndexed { index, detailLayout ->
            canvas.save()
            canvas.translate(left + horizontalPadding, textTop)
            detailLayout.draw(canvas)
            canvas.restore()
            textTop += detailLayout.height
            if (index < detailLayouts.lastIndex) textTop += detailGap
        }
    }

    private fun buildTextLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int
    ): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setIncludePad(false)
        .setLineSpacing(0f, 1f)
        .setMaxLines(maxLines)
        .build()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && event.x > timeColumnWidth) {
            val day = ((event.x - timeColumnWidth) / dayWidth).toInt() + 1
            val section = (event.y / sectionHeight).toInt() + 1
            if (day > visibleDaysCount) return true
            visibleCourses().firstOrNull { course ->
                course.dayOfWeek == day &&
                    section in course.startSection..course.endSection
            }?.let { course ->
                performClick()
                onCourseClickListener?.invoke(course)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        return accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    }

    fun setCourses(courses: List<Course>) {
        this.courses = courses
        accessibilityHelper.invalidateRoot()
        invalidate()
    }

    fun setCurrentWeek(week: Int) {
        currentWeek = week
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
        accessibilityHelper.invalidateRoot()
        requestLayout()
        invalidate()
    }

    fun setOnCourseClickListener(listener: (Course) -> Unit) {
        onCourseClickListener = listener
    }

    private fun visibleCourses(): List<Course> = courses.filter {
        it.dayOfWeek <= visibleDaysCount && ScheduleRules.isCourseInWeek(it, currentWeek)
    }

    private fun courseBounds(course: Course): RectF = RectF(
        timeColumnWidth + (course.dayOfWeek - 1) * dayWidth + courseInset,
        (course.startSection - 1) * sectionHeight + courseInset,
        timeColumnWidth + course.dayOfWeek * dayWidth - courseInset,
        course.endSection * sectionHeight - courseInset
    )

    private fun courseDescription(course: Course): String {
        val days = resources.getStringArray(R.array.weekdays)
        val weekType = when (course.weekType) {
            1 -> resources.getString(R.string.odd_week)
            2 -> resources.getString(R.string.even_week)
            else -> resources.getString(R.string.every_week)
        }
        return resources.getString(
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
