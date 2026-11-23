package com.eva.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp


val lightScheme = lightColorScheme(
	primary = PrimaryLight,
	onPrimary = OnPrimaryLight,
	primaryContainer = PrimaryContainerLight,
	onPrimaryContainer = OnPrimaryContainerLight,
	inversePrimary = InversePrimaryLight,
	secondary = SecondaryLight,
	onSecondary = OnSecondaryLight,
	secondaryContainer = SecondaryContainerLight,
	onSecondaryContainer = OnSecondaryContainerLight,
	tertiary = TertiaryLight,
	onTertiary = OnTertiaryLight,
	tertiaryContainer = TertiaryContainerLight,
	onTertiaryContainer = OnTertiaryContainerLight,
	background = BackgroundLight,
	onBackground = OnBackgroundLight,
	surface = SurfaceLight,
	onSurface = OnSurfaceLight,
	surfaceVariant = SurfaceVariantLight,
	onSurfaceVariant = OnSurfaceVariantLight,
	surfaceTint = SurfaceTintLight,
	inverseSurface = InverseSurfaceLight,
	inverseOnSurface = InverseOnSurfaceLight,
	error = ErrorLight,
	onError = OnErrorLight,
	errorContainer = ErrorContainerLight,
	onErrorContainer = OnErrorContainerLight,
	outline = OutlineLight,
	outlineVariant = OutlineVariantLight,
	scrim = ScrimLight,
	surfaceBright = SurfaceBrightLight,
	surfaceContainer = SurfaceContainerLight,
	surfaceContainerHigh = SurfaceContainerHighLight,
	surfaceContainerHighest = SurfaceContainerHighestLight,
	surfaceContainerLow = SurfaceContainerLowLight,
	surfaceContainerLowest = SurfaceContainerLowestLight,
	surfaceDim = SurfaceDimLight,
	primaryFixed = PrimaryFixed,
	primaryFixedDim = PrimaryFixedDim,
	onPrimaryFixed = OnPrimaryFixed,
	onPrimaryFixedVariant = OnPrimaryFixedVariant,
	secondaryFixed = SecondaryFixed,
	secondaryFixedDim = SecondaryFixedDim,
	onSecondaryFixed = OnSecondaryFixed,
	onSecondaryFixedVariant = OnSecondaryFixedVariant,
	tertiaryFixed = TertiaryFixed,
	tertiaryFixedDim = TertiaryFixedDim,
	onTertiaryFixed = OnTertiaryFixed,
	onTertiaryFixedVariant = OnTertiaryFixedVariant,
).copy(
	primary = Color(0xFFA54127),
	onPrimary = Color.White,
	primaryContainer = Color(0xFFF4DFD4),
	onPrimaryContainer = Color(0xFF3D180A),
	secondary = Color(0xFF745548),
	onSecondary = Color.White,
	secondaryContainer = Color(0xFFF4DFD4),
	onSecondaryContainer = Color(0xFF40251B),
	background = Color(0xFFFAF7F0),
	surface = Color(0xFFFAF7F0),
	surfaceContainerLowest = Color(0xFFFFFCF7),
	surfaceContainerLow = Color(0xFFF8F3EA),
	surfaceContainer = Color(0xFFF1EBE2),
	surfaceContainerHigh = Color(0xFFECE4DA),
	surfaceContainerHighest = Color(0xFFE6DCD2),
	onSurface = Color(0xFF211D24),
	onBackground = Color(0xFF211D24),
	onSurfaceVariant = Color(0xFF665B55),
	outlineVariant = Color(0xFFE2D7CB),
)

val darkScheme = darkColorScheme(
	primary = PrimaryDark,
	onPrimary = OnPrimaryDark,
	primaryContainer = PrimaryContainerDark,
	onPrimaryContainer = OnPrimaryContainerDark,
	inversePrimary = InversePrimaryDark,
	secondary = SecondaryDark,
	onSecondary = OnSecondaryDark,
	secondaryContainer = SecondaryContainerDark,
	onSecondaryContainer = OnSecondaryContainerDark,
	tertiary = TertiaryDark,
	onTertiary = OnTertiaryDark,
	tertiaryContainer = TertiaryContainerDark,
	onTertiaryContainer = OnTertiaryContainerDark,
	background = BackgroundDark,
	onBackground = OnBackgroundDark,
	surface = SurfaceDark,
	onSurface = OnSurfaceDark,
	surfaceVariant = SurfaceVariantDark,
	onSurfaceVariant = OnSurfaceVariantDark,
	surfaceTint = SurfaceTintDark,
	inverseSurface = InverseSurfaceDark,
	inverseOnSurface = InverseOnSurfaceDark,
	error = ErrorDark,
	onError = OnErrorDark,
	errorContainer = ErrorContainerDark,
	onErrorContainer = OnErrorContainerDark,
	outline = OutlineDark,
	outlineVariant = OutlineVariantDark,
	scrim = ScrimDark,
	surfaceBright = SurfaceBrightDark,
	surfaceContainer = SurfaceContainerDark,
	surfaceContainerHigh = SurfaceContainerHighDark,
	surfaceContainerHighest = SurfaceContainerHighestDark,
	surfaceContainerLow = SurfaceContainerLowDark,
	surfaceContainerLowest = SurfaceContainerLowestDark,
	surfaceDim = SurfaceDimDark,
	primaryFixed = PrimaryFixed,
	primaryFixedDim = PrimaryFixedDim,
	onPrimaryFixed = OnPrimaryFixed,
	onPrimaryFixedVariant = OnPrimaryFixedVariant,
	secondaryFixed = SecondaryFixed,
	secondaryFixedDim = SecondaryFixedDim,
	onSecondaryFixed = OnSecondaryFixed,
	onSecondaryFixedVariant = OnSecondaryFixedVariant,
	tertiaryFixed = TertiaryFixed,
	tertiaryFixedDim = TertiaryFixedDim,
	onTertiaryFixed = OnTertiaryFixed,
	onTertiaryFixedVariant = OnTertiaryFixedVariant,
).copy(
	primary = Color(0xFFFFB69A),
	onPrimary = Color(0xFF552109),
	primaryContainer = Color(0xFF633420),
	onPrimaryContainer = Color(0xFFFFDBCA),
	background = Color(0xFF1D191D),
	surface = Color(0xFF1D191D),
	surfaceContainerLowest = Color(0xFF191519),
	surfaceContainerLow = Color(0xFF251F26),
	surfaceContainer = Color(0xFF30282E),
	surfaceContainerHigh = Color(0xFF3B3036),
	onSurface = Color(0xFFF3E8E4),
	onBackground = Color(0xFFF3E8E4),
	onSurfaceVariant = Color(0xFFD0BFBA),
	outlineVariant = Color(0xFF55474C),
)

@Composable
fun RecorderAppTheme(
	darkTheme: Boolean = isSystemInDarkTheme(),
	// Dynamic color is available on Android 12+
	dynamicColor: Boolean = false,
	content: @Composable () -> Unit,
) {
	val context = LocalContext.current

	val colorScheme = when {
		dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
			if (darkTheme) dynamicDarkColorScheme(context)
			else dynamicLightColorScheme(context)
		}

		darkTheme -> darkScheme
		else -> lightScheme
	}

	MaterialTheme(
		colorScheme = colorScheme,
		typography = AppTypography,
		shapes = JournalShapes,
		content = content
	)
}

private val JournalShapes = Shapes(
	small = RoundedCornerShape(8.dp),
	medium = RoundedCornerShape(12.dp),
	large = RoundedCornerShape(16.dp),
	extraLarge = RoundedCornerShape(22.dp),
)
