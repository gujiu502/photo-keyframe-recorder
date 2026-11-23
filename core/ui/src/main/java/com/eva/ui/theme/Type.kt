package com.eva.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

val AppTypography = Typography().run {
	copy(
		headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
		headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
		titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
		titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
	)
}
