package com.tck.factoryflow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Standardized shapes and corner radii for TCK FactoryFlow.
 */
object TCKShapes {
    val pill = RoundedCornerShape(50)
    val extraSmall = RoundedCornerShape(4.dp)
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val extraLarge = RoundedCornerShape(20.dp)
    val bottomSheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}
