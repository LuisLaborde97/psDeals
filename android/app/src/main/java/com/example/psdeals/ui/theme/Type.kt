package com.example.psdeals.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.psdeals.R

/** Poppins empaquetada en res/font (licencia OFL en assets/licenses): disponible offline y sin depender de GMS. */
val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
)

private val Default = Typography()

private fun TextStyle.poppins() = copy(fontFamily = Poppins)

// Conserva tamaños, interlineado y pesos de Material 3; solo cambia la familia en los 15 estilos.
val Typography = Typography(
    displayLarge = Default.displayLarge.poppins(),
    displayMedium = Default.displayMedium.poppins(),
    displaySmall = Default.displaySmall.poppins(),
    headlineLarge = Default.headlineLarge.poppins(),
    headlineMedium = Default.headlineMedium.poppins(),
    headlineSmall = Default.headlineSmall.poppins(),
    titleLarge = Default.titleLarge.poppins(),
    titleMedium = Default.titleMedium.poppins(),
    titleSmall = Default.titleSmall.poppins(),
    bodyLarge = Default.bodyLarge.poppins(),
    bodyMedium = Default.bodyMedium.poppins(),
    bodySmall = Default.bodySmall.poppins(),
    labelLarge = Default.labelLarge.poppins(),
    labelMedium = Default.labelMedium.poppins(),
    labelSmall = Default.labelSmall.poppins(),
)
