package com.courseschedule.ui

import android.graphics.Typeface
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.courseschedule.R
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Semester
import com.courseschedule.databinding.ItemWeekScheduleBinding
import com.courseschedule.domain.ScheduleRules
import com.courseschedule.domain.SemesterPhase
import com.courseschedule.domain.SemesterWeekStatus
import com.courseschedule.utils.SchedulePreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class WeekPageSettings(
    val showWeekend: Boolean = true,
    val showTimes: Boolean = true,
    val showInactiveCourses: Boolean = true,
    val sectionHeightDp: Int = 64,
    val sectionTimes: List<String> = SchedulePreferences.DEFAULT_SECTION_TIMES,
    val sectionEndTimes: List<String> = SchedulePreferences.DEFAULT_SECTION_END_TIMES
)

class WeekPagerAdapter(
    private val onCourseClick: (Course, View, RectF) -> Unit,
    private val onAddCourse: (dayOfWeek: Int?, section: Int?) -> Unit,
    private val onQuickAddCourse:
        (week: Int, dayOfWeek: Int, startSection: Int, endSection: Int) -> Unit
) : RecyclerView.Adapter<WeekPagerAdapter.WeekViewHolder>() {

    private var semester: Semester? = null
    private var courses: List<Course> = emptyList()
    private var status: SemesterWeekStatus? = null
    private var settings = WeekPageSettings()
    private val scrollPositions = mutableMapOf<Int, Int>()
    private val settleInterpolator = PathInterpolator(0.2f, 0.85f, 0.25f, 1f)

    init {
        setHasStableIds(true)
    }

    fun submitData(
        semester: Semester?,
        courses: List<Course>,
        status: SemesterWeekStatus?,
        settings: WeekPageSettings
    ) {
        if (this.semester == semester && this.courses == courses &&
            this.status == status && this.settings == settings) return
        val oldCount = itemCount
        val oldSemesterId = this.semester?.id
        this.semester = semester
        this.courses = courses
        this.status = status
        this.settings = settings
        if (oldCount != itemCount || oldSemesterId != semester?.id) {
            scrollPositions.clear()
            notifyDataSetChanged()
        } else if (itemCount > 0) {
            // Reuse the holder during state sync so its running entrance clock survives.
            notifyItemRangeChanged(0, itemCount, Unit)
        }
    }

    override fun getItemCount(): Int = semester?.totalWeeks ?: 0

    override fun getItemId(position: Int): Long =
        (semester?.id ?: 0L) * 100L + position + 1L

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WeekViewHolder {
        val binding = ItemWeekScheduleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return WeekViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WeekViewHolder, position: Int) {
        val semester = semester ?: return
        holder.bind(
            week = position + 1,
            semester = semester,
            courses = courses,
            status = status,
            settings = settings
        )
    }

    override fun onViewRecycled(holder: WeekViewHolder) {
        holder.boundWeek?.let { week ->
            scrollPositions[week] = holder.binding.scheduleScroll.scrollY
        }
        holder.binding.courseTableView.cancelReturnEntrance()
        holder.resetSelectionMotion()
        holder.binding.courseTableView.resetPagerMotion()
        super.onViewRecycled(holder)
    }

    fun currentHolder(pager: ViewPager2): WeekViewHolder? =
        (pager.getChildAt(0) as? RecyclerView)
            ?.findViewHolderForAdapterPosition(pager.currentItem) as? WeekViewHolder

    fun playSelectionMotion(pager: ViewPager2, position: Int, forward: Boolean): Boolean {
        val recyclerView = pager.getChildAt(0) as? RecyclerView ?: return false
        val holder = recyclerView.findViewHolderForAdapterPosition(position) as? WeekViewHolder
            ?: return false
        holder.playSelectionMotion(forward)
        return true
    }

    inner class WeekViewHolder(
        val binding: ItemWeekScheduleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        var boundWeek: Int? = null

        fun bind(
            week: Int,
            semester: Semester,
            courses: List<Course>,
            status: SemesterWeekStatus?,
            settings: WeekPageSettings
        ) {
            val previousWeek = boundWeek
            if (previousWeek != week) {
                resetSelectionMotion()
                binding.courseTableView.resetPagerMotion()
            }
            boundWeek = week
            val displayCourses = ScheduleRules.selectCoursesForWeek(
                courses,
                week,
                settings.showInactiveCourses
            )
            val visibleCourses = if (settings.showWeekend) {
                displayCourses
            } else {
                displayCourses.filter { it.dayOfWeek <= 5 }
            }

            bindDayHeaders(week, semester, status, settings.showWeekend)
            binding.courseTableView.applyDisplaySettings(
                showWeekend = settings.showWeekend,
                showTimes = settings.showTimes,
                sectionHeightDp = settings.sectionHeightDp,
                sectionTimes = settings.sectionTimes,
                sectionEndTimes = settings.sectionEndTimes
            )
            binding.courseTableView.setCurrentWeek(week)
            binding.courseTableView.setCourses(displayCourses)
            binding.courseTableView.setOnCourseClickListener(onCourseClick)
            binding.courseTableView.setOnQuickAddCourseListener { day, startSection, endSection ->
                onQuickAddCourse(week, day, startSection, endSection)
            }
            binding.courseTableView.setOnQuickAddSelectionChangedListener { active ->
                binding.emptyState.visibility = if (visibleCourses.isEmpty() && !active) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
            binding.emptyState.visibility = if (visibleCourses.isEmpty()) View.VISIBLE else View.GONE
            binding.btnEmptyAdd.setOnClickListener { onAddCourse(null, null) }
            binding.btnEmptyAdd.installPressScale(0.97f)
            binding.root.contentDescription = binding.root.context.getString(R.string.week_format, week)

            if (previousWeek != week) {
                binding.scheduleScroll.post {
                    binding.scheduleScroll.scrollTo(0, scrollPositions[week] ?: 0)
                }
            }
        }

        fun playSelectionMotion(forward: Boolean) {
            resetSelectionMotion()
            val density = binding.root.resources.displayMetrics.density

            binding.weekDayHeader.apply {
                alpha = 0.55f
                translationX = (if (forward) 12f else -12f) * density
                animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(360L)
                    .setInterpolator(settleInterpolator)
                    .withLayer()
                    .start()
            }
            if (binding.emptyState.visibility != View.VISIBLE) return

            binding.emptyState.apply {
                alpha = 0f
                translationY = 10f * density
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(540L)
                    .setInterpolator(settleInterpolator)
                    .withLayer()
                    .start()
            }
            binding.emptyIconContainer.apply {
                alpha = 0f
                scaleX = 0.96f
                scaleY = 0.96f
                rotation = 0f
                translationY = 4f * density
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setStartDelay(45L)
                    .setDuration(620L)
                    .setInterpolator(settleInterpolator)
                    .withLayer()
                    .start()
            }
            binding.tvEmptyTitle.apply {
                alpha = 0f
                translationY = 16f * density
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(145L)
                    .setDuration(430L)
                    .setInterpolator(settleInterpolator)
                    .start()
            }
            binding.btnEmptyAdd.apply {
                alpha = 0f
                translationY = 18f * density
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(230L)
                    .setDuration(460L)
                    .setInterpolator(settleInterpolator)
                    .withLayer()
                    .start()
            }
        }

        fun resetSelectionMotion() {
            listOf(
                binding.weekDayHeader,
                binding.scheduleScroll,
                binding.emptyState,
                binding.emptyIconContainer,
                binding.tvEmptyTitle,
                binding.btnEmptyAdd
            ).forEach { view ->
                view.animate().cancel()
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                view.translationX = 0f
                view.translationY = 0f
                view.rotation = 0f
            }
        }

        private fun bindDayHeaders(
            week: Int,
            semester: Semester,
            status: SemesterWeekStatus?,
            showWeekend: Boolean
        ) {
            val context = binding.root.context
            val dayViews = listOf(
                binding.tvDay1, binding.tvDay2, binding.tvDay3,
                binding.tvDay4, binding.tvDay5, binding.tvDay6, binding.tvDay7
            )
            binding.tvDay6.visibility = if (showWeekend) View.VISIBLE else View.GONE
            binding.tvDay7.visibility = if (showWeekend) View.VISIBLE else View.GONE

            val days = context.resources.getStringArray(R.array.weekdays)
            val descriptionFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
            val calendar = Calendar.getInstance().apply {
                timeInMillis = semester.startDate
                add(Calendar.DAY_OF_MONTH, (week - 1) * 7)
            }
            binding.tvMonthLabel.text = "${calendar.get(Calendar.MONTH) + 1}\n月"
            val today = Calendar.getInstance()
            val todayIndex = when (today.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                Calendar.SUNDAY -> 6
                else -> 0
            }
            val showToday = status?.phase == SemesterPhase.ACTIVE && week == status.week
            val normalColor = ContextCompat.getColor(context, R.color.text_secondary)
            val highlightColor = ContextCompat.getColor(context, R.color.primary)

            dayViews.forEachIndexed { index, textView ->
                val weekday = days.getOrElse(index) { "" }
                textView.text = "${weekday.removePrefix("周")}\n${calendar.get(Calendar.DAY_OF_MONTH)}"
                textView.contentDescription = descriptionFormat.format(calendar.time)
                styleDay(textView, showToday && index == todayIndex, normalColor, highlightColor)
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        private fun styleDay(
            textView: TextView,
            selected: Boolean,
            normalColor: Int,
            highlightColor: Int
        ) {
            if (selected) {
                textView.setTextColor(highlightColor)
                textView.setTypeface(null, Typeface.BOLD)
                textView.setBackgroundResource(R.drawable.bg_day_selected)
            } else {
                textView.setTextColor(normalColor)
                textView.setTypeface(null, Typeface.NORMAL)
                textView.background = null
            }
        }
    }
}
