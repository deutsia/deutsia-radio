package com.opensource.i2pradio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape tokens used throughout the app.
 *
 * Values mirror the corner radii used in the existing XML layouts:
 *  - Material cards:           16-24dp (M3 large/extraLarge)
 *  - Browse carousel cards:    20dp
 *  - Station list items:       24dp (ExtraLarge)
 *  - Now Playing cover card:   24dp (ExtraLarge)
 *  - Mini player (top only):   20dp (see [MiniPlayerShape])
 */
val DeutsiaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Mini-player has rounded top corners only (see ShapeAppearance.MiniPlayer
 * in res/values/themes.xml).
 */
val MiniPlayerShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = 0.dp,
    bottomEnd = 0.dp,
)
