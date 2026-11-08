package com.aartiq.aavija

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/** Backdrop source shared by the home screen's frosted panels. */
val LocalAavijaHazeState = compositionLocalOf<HazeState?> { null }

@Composable
fun Modifier.aavijaBackdropBlur(): Modifier {
    val hazeState = LocalAavijaHazeState.current ?: return this
    return hazeEffect(state = hazeState)
}
