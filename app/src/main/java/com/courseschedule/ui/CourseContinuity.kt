package com.courseschedule.ui

import com.courseschedule.data.entity.Course

internal data class CourseContinuityPair(val from: Course, val to: Course)

internal object CourseContinuity {
    private data class Identity(val name: String, val teacher: String)
    private fun identity(course: Course) = Identity(course.courseName.trim(), course.teacher.trim())

    fun match(from: List<Course>, to: List<Course>): List<CourseContinuityPair> {
        val incoming = to.associateBy { it.id }
        val exact = from.mapNotNull { course ->
            incoming[course.id]?.takeIf { course.id > 0L }?.let { CourseContinuityPair(course, it) }
        }
        val matchedFrom = exact.map { it.from.id }.toSet()
        val matchedTo = exact.map { it.to.id }.toSet()
        val remainingFrom = from.filter { it.id !in matchedFrom }.groupBy(::identity)
        val remainingTo = to.filter { it.id !in matchedTo }.groupBy(::identity)
        val related = remainingFrom.flatMap { (identity, courses) ->
            val destinations = remainingTo[identity].orEmpty()
            val sameSlot = courses.mapNotNull { source ->
                destinations.filter { sameSlot(source, it) }.singleOrNull()?.let { target ->
                    if (courses.count { sameSlot(it, target) } == 1) CourseContinuityPair(source, target) else null
                }
            }
            val left = courses.filter { course -> sameSlot.none { it.from.id == course.id } }
            val right = destinations.filter { course -> sameSlot.none { it.to.id == course.id } }
            // Only an unambiguous remaining occurrence may move to a different slot.
            sameSlot + if (left.size == 1 && right.size == 1) {
                listOf(CourseContinuityPair(left.single(), right.single()))
            } else emptyList()
        }
        return exact + related
    }

    fun sameSlot(from: Course, to: Course): Boolean = from.dayOfWeek == to.dayOfWeek &&
        from.startSection == to.startSection && from.endSection == to.endSection
}
