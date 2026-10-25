package no.oktodrive.app

/**
 * Shared UI/control concepts for both phone and dedicated PoC/puck radio layouts.
 * The underlying Mumble/audio engine must stay independent of presentation.
 */
internal enum class UiMode {
    AUTO,
    MOBILE,
    RADIO
}

internal enum class RadioAction {
    PTT,
    SPEAKER_MUTE,
    MICROPHONE_MUTE,
    DUAL_WATCH,
    NEXT_CHANNEL,
    PREVIOUS_CHANNEL
}

internal data class RadioUiState(
    val mode: UiMode = UiMode.AUTO,
    val speakerMuted: Boolean = false,
    val microphoneMuted: Boolean = false
)
