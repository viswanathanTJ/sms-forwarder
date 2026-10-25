package com.viswa2k.smsforwarder.ui.theme

import androidx.compose.ui.graphics.Color

// Palette from the owner-approved mock (docs/superpowers/specs/2026-10-25-forwarder-revamp-mock.html).
val BrandPrimary = Color(0xFF2F5BEA)
val HeroGradientEnd = Color(0xFF5B3FE0)
val TelegramBlue = Color(0xFF229ED9)

// Light
val LightBackground = Color(0xFFF4F5F9)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEBEDF3)
val LightText = Color(0xFF14171F)
val LightMuted = Color(0xFF5D6475)
val LightLine = Color(0xFFE2E5EC)
val LightPrimarySoft = Color(0xFFE5ECFF)
val LightError = Color(0xFFC62F2F)
val LightErrorSoft = Color(0xFFFDE6E6)

// Dark
val DarkBackground = Color(0xFF0E1118)
val DarkSurface = Color(0xFF171B25)
val DarkSurfaceVariant = Color(0xFF212633)
val DarkText = Color(0xFFE8EAF0)
val DarkMuted = Color(0xFF9AA2B5)
val DarkLine = Color(0xFF2A3040)
val DarkPrimary = Color(0xFF8CA6FF)
val DarkPrimarySoft = Color(0xFF243063)
val DarkError = Color(0xFFFF8A8A)
val DarkErrorSoft = Color(0xFF4A2024)

/** Foreground + soft background for a coloured badge/chip. */
data class Tone(val fg: Color, val soft: Color)

/** Colours the M3 scheme has no slot for: message types, delivery states, Telegram. */
data class ExtraColors(
    val otp: Tone,
    val transaction: Tone,
    val missedCall: Tone,
    val ok: Tone,
    val warn: Tone,
    val neutral: Tone,
    val telegram: Color,
)

val LightExtraColors = ExtraColors(
    otp = Tone(Color(0xFF7A3FE0), Color(0xFFEFE6FF)),
    transaction = Tone(Color(0xFF0B7D73), Color(0xFFDFF5F2)),
    missedCall = Tone(Color(0xFFC2410C), Color(0xFFFFEADF)),
    ok = Tone(Color(0xFF178A4C), Color(0xFFE2F5EA)),
    warn = Tone(Color(0xFFB26A00), Color(0xFFFFF2DC)),
    neutral = Tone(LightMuted, LightBackground),
    telegram = TelegramBlue,
)

val DarkExtraColors = ExtraColors(
    otp = Tone(Color(0xFFB794F6), Color(0xFF2F2150)),
    transaction = Tone(Color(0xFF4FD1C5), Color(0xFF0F3431)),
    missedCall = Tone(Color(0xFFFB923C), Color(0xFF3F2312)),
    ok = Tone(Color(0xFF5FD38D), Color(0xFF143222)),
    warn = Tone(Color(0xFFF5B54A), Color(0xFF3B2B0E)),
    neutral = Tone(DarkMuted, DarkSurfaceVariant),
    telegram = Color(0xFF4FB8EA),
)
