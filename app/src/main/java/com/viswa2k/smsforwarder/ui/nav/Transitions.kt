package com.viswa2k.smsforwarder.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Every transition spec lives here so the durations are declared once (all <= 250 ms). */
object Transitions {
    const val TAB_MS = 200
    const val TAB_EXIT_FADE_MS = 120
    const val PUSH_MS = 220
    const val PUSH_UNDER_FADE_MS = 100
    const val FADE_MS = 250
    const val CONTENT_MS = 150

    /** The new tab slides in by 1/10 of the width. */
    const val SLIDE_FRACTION = 10
    const val PUSH_INITIAL_SCALE = 0.94f
}

enum class TabDirection { LEFT, RIGHT, NONE }

/** Which way the new tab sits relative to the old one; NONE when either route is not a tab or they are equal. */
fun tabDirection(fromRoute: String?, toRoute: String?): TabDirection {
    val from = Routes.TABS.indexOf(fromRoute)
    val to = Routes.TABS.indexOf(toRoute)
    return when {
        from < 0 || to < 0 || from == to -> TabDirection.NONE
        to > from -> TabDirection.RIGHT
        else -> TabDirection.LEFT
    }
}

private fun <T> tabSpec(ms: Int): FiniteAnimationSpec<T> = tween(ms, easing = FastOutSlowInEasing)

/** Shared-axis X: the new tab enters from the side of its index while fading in. */
fun tabEnter(fromRoute: String?, toRoute: String?): EnterTransition = when (tabDirection(fromRoute, toRoute)) {
    TabDirection.NONE -> EnterTransition.None
    TabDirection.RIGHT -> slideInHorizontally(tabSpec(Transitions.TAB_MS)) { it / Transitions.SLIDE_FRACTION } +
        fadeIn(tabSpec(Transitions.TAB_MS))
    TabDirection.LEFT -> slideInHorizontally(tabSpec(Transitions.TAB_MS)) { -it / Transitions.SLIDE_FRACTION } +
        fadeIn(tabSpec(Transitions.TAB_MS))
}

/** The old tab slides the other way and is gone by 120 ms so the two never overlap visibly. */
fun tabExit(fromRoute: String?, toRoute: String?): ExitTransition = when (tabDirection(fromRoute, toRoute)) {
    TabDirection.NONE -> ExitTransition.None
    TabDirection.RIGHT -> slideOutHorizontally(tabSpec(Transitions.TAB_MS)) { -it / Transitions.SLIDE_FRACTION } +
        fadeOut(tabSpec(Transitions.TAB_EXIT_FADE_MS))
    TabDirection.LEFT -> slideOutHorizontally(tabSpec(Transitions.TAB_MS)) { it / Transitions.SLIDE_FRACTION } +
        fadeOut(tabSpec(Transitions.TAB_EXIT_FADE_MS))
}

/** Shared-axis Z for a pushed screen (rule editor). */
fun pushEnter(): EnterTransition =
    fadeIn(tabSpec(Transitions.PUSH_MS)) + scaleIn(tabSpec(Transitions.PUSH_MS), initialScale = Transitions.PUSH_INITIAL_SCALE)

/** Reverse of [pushEnter]; also used when the pushed screen is left for a tab. */
fun pushPopExit(): ExitTransition =
    fadeOut(tabSpec(Transitions.PUSH_MS)) + scaleOut(tabSpec(Transitions.PUSH_MS), targetScale = Transitions.PUSH_INITIAL_SCALE)

fun fade(): EnterTransition = fadeIn(tabSpec(Transitions.FADE_MS))

fun fadeAway(): ExitTransition = fadeOut(tabSpec(Transitions.FADE_MS))

/** In-screen show/hide (AnimatedVisibility). */
fun contentEnter(): EnterTransition =
    fadeIn(tween(Transitions.CONTENT_MS)) + expandVertically(tween(Transitions.CONTENT_MS))

fun contentExit(): ExitTransition =
    fadeOut(tween(Transitions.CONTENT_MS)) + shrinkVertically(tween(Transitions.CONTENT_MS))
