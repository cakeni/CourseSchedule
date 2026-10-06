package com.courseschedule.ui

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.courseschedule.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlin.math.max
import kotlin.math.roundToInt

/** Keep every primary page's controls safe while its glass reaches the screen edge. */
internal fun AppCompatActivity.installPrimaryNavigationGlass(
    root: ViewGroup,
    glass: ScheduleNavigationGlass,
    navigation: BottomNavigationView,
    source: View,
    scroll: NestedScrollView,
    action: View? = null
) {
    navigation.setBackgroundColor(Color.TRANSPARENT)
    navigation.elevation = 0f
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    WindowCompat.getInsetsController(window, root).apply {
        isAppearanceLightStatusBars = resources.getBoolean(R.bool.window_light_system_bars)
        isAppearanceLightNavigationBars = isAppearanceLightStatusBars
    }
    val density = resources.displayMetrics.density
    val originalBottom = scroll.paddingBottom
    var bottomAvoidance = 0
    var safeBottom = 0
    fun updateScrollPadding() {
        val actionSpace = action?.takeIf { it.visibility != View.GONE }?.let { control ->
            val margins = control.layoutParams as? ViewGroup.MarginLayoutParams
            max(control.height, control.minimumHeight) + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
        } ?: 0
        val bottom = originalBottom + (72f * density).roundToInt() + bottomAvoidance + actionSpace
        if (scroll.paddingBottom != bottom) scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, bottom)
        // The todo action floats on the same glass instead of covering sharp text.
        val glassParams = glass.layoutParams as ViewGroup.MarginLayoutParams
        val height = (108f * density).roundToInt() + safeBottom + actionSpace
        val glassBottom = bottomAvoidance - safeBottom
        if (glassParams.height != height || glassParams.bottomMargin != glassBottom) {
            glassParams.height = height
            glassParams.bottomMargin = glassBottom
            glass.layoutParams = glassParams
        }
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        safeBottom = safe.bottom
        bottomAvoidance = max(safe.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
        view.setPadding(safe.left, safe.top, safe.right, 0)
        val navParams = navigation.layoutParams as ViewGroup.MarginLayoutParams
        if (navParams.bottomMargin != bottomAvoidance) {
            navParams.bottomMargin = bottomAvoidance
            navigation.layoutParams = navParams
        }
        navigation.setPadding(0, 0, 0, 0)
        updateScrollPadding()
        WindowInsetsCompat.CONSUMED
    }
    action?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateScrollPadding() }
    glass.bind(source)
    ViewCompat.requestApplyInsets(root)
}
