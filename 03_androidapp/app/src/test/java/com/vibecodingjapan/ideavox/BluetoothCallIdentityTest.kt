package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothCallIdentityTest {
  @Test fun numericAddressSurvivesTheHfpDialCharacterFilter() {
    assertEquals(BluetoothCallIdentity.NUMERIC_ADDRESS, BluetoothCallIdentity.hfpDialable(BluetoothCallIdentity.NUMERIC_ADDRESS))
    assertTrue(BluetoothCallIdentity.isHfpDialable(BluetoothCallIdentity.NUMERIC_ADDRESS))
  }

  @Test fun legacyAlphabeticAddressBecomesAnEmptyHfpNumber() {
    // This reproduces the CLCC filter in btif_hf.cc: only dial characters are
    // forwarded, so an alphabetic-only scheme-specific part is sent as "".
    assertEquals("", BluetoothCallIdentity.hfpDialable(BluetoothCallIdentity.LEGACY_ALPHA_ADDRESS))
    assertFalse(BluetoothCallIdentity.isHfpDialable(BluetoothCallIdentity.LEGACY_ALPHA_ADDRESS))
    assertFalse(BluetoothCallIdentity.isHfpDialable(null))
  }

  @Test fun addressSelectorDefaultsToNumericAndOnlyUsesLegacyAlphaOnRequest() {
    assertEquals(BluetoothCallIdentity.NUMERIC_ADDRESS, BluetoothCallIdentity.address(legacyAlphaAddress = false))
    assertEquals(BluetoothCallIdentity.LEGACY_ALPHA_ADDRESS, BluetoothCallIdentity.address(legacyAlphaAddress = true))
  }

  @Test fun hfpDialableFilterKeepsDtmfCharacters() {
    assertEquals("+81*123#", BluetoothCallIdentity.hfpDialable("+81*123#"))
  }
}
