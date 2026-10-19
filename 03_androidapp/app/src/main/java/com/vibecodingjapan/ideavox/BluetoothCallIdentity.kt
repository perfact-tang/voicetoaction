package com.vibecodingjapan.ideavox

/**
 * Identity used for the app's self-managed, local VoIP call.
 *
 * The call never reaches a carrier. It exists only so the Bluetooth stack
 * publishes an HFP call to the headset, which lets the headset deliver its
 * call button (hang up / answer) to this app.
 *
 * Important: Android's HFP CLCC response filters the scheme-specific part of
 * the call URI through the native dialable-character check
 * (`utl_isdialchar`) in `btif_hf.cc`. An alphabetic-only identifier such as
 * `ideavox-bluetooth-recording` is therefore published to the headset as an
 * empty number. A numeric address survives that filter, so it is the default.
 */
internal object BluetoothCallIdentity {
  /**
   * Numeric local VoIP address. This is the same fictitious value used by the
   * earlier Debug comparison, so new logs can be compared with older probes.
   * No carrier call is ever placed for it.
   */
  const val NUMERIC_ADDRESS = "5550100"

  /**
   * Old alphabetic address, kept only for a controlled Debug comparison.
   * Android's HFP stack filters this to an empty CLCC number.
   */
  const val LEGACY_ALPHA_ADDRESS = "ideavox-bluetooth-recording"

  fun address(legacyAlphaAddress: Boolean): String =
    if (legacyAlphaAddress) LEGACY_ALPHA_ADDRESS else NUMERIC_ADDRESS

  /**
   * Mirrors the part of the HFP CLCC number filter that matters here: only
   * dial characters are forwarded. Letters are dropped, so an alphabetic
   * address becomes an empty string.
   */
  fun hfpDialable(value: String?): String =
    value.orEmpty().filter { it in '0'..'9' || it == '+' || it == '*' || it == '#' }

  fun isHfpDialable(value: String?): Boolean = hfpDialable(value).isNotEmpty()
}
