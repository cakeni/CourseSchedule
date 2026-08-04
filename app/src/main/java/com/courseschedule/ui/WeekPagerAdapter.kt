package com.courseschedule.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
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
    val sectionHeightDp: Int = 64,
    val sectionTimes: List<String> = SchedulePreferences.DEFAULT_SECTION_TIMES
)

class WeekPagerAdapter(
    private val onCourseClick: (Course) -> Unit,
    private val onAddCourse: () -> Unit
) : RecyclerView.Adapter<WeekPagerAdapter.WeekViewHolder>() {

    private var semester: Semester? = null
    private var courses: List<Course> = emptyList()
    private var status: SemesterWeekStatus? = null
    private var settings = WeekPageSettings()
    private val scrollPositions = mutableMapOf<Int, Int>()

    init {
        setHasStableIds(true)
    }

    fun submitData(
        semester: Semester?,
        courses: List<Course>,
        status: SemesterWeekStatus?,
        settings: WeekPageSettings
    ) {
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
            notifyItemRangeChanged(0, itemCount)
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
        super.onViewRecycled(holder)
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
            boundWeek = week
            val pageCourses = courses.filter { ScheduleRules.isCourseInWeek(it, week) }
            val visibleCourses = if (settings.showWeekend) {
                pageCourses
            } else {
                pageCourses.filter { it.dayOfWeek <= 5 }
            }

            bindDayHeaders(week, semester, status, settings.showWeekend)
            binding.courseTableView.applyDisplaySettings(
                showWeekend = settings.showWeekend,
                showTimes = settings.showTimes,
                sectionHeightDp = settings.sectionHeightDp,
                sectionTimes = settings.sectionTimes
            )
            binding.courseTableView.setCurrentWeek(week)
            binding.courseTableView.setCourses(pageCourses)
            binding.courseTableView.setOnCourseClickListener(onCourseClick)
            binding.emptyState.visibility = if (visibleCourses.isEmpty()) View.VISIBLE else View.GONE
            binding.btnEmptyAdd.setOnClickListener { onAddCourse() }
            binding.root.contentDescription = binding.root.context.getString(R.string.week_format, week)

            if (previousWeek != week) {
                binding.scheduleScroll.post {
                    binding.scheduleScroll.scrollTo(0, scrollPositions[week] ?: 0)
                }
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
            binding.courseTableView.setHighlightedDay(if (showToday) todayIndex + 1 else null)
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
