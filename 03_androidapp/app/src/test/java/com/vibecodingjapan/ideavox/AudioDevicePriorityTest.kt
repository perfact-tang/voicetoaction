package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioDevicePriorityTest {
  private data class Device(val id: Int, val name: String?)

  private fun pick(devices: List<Device>, id: Int?, name: String?): Device? =
    AudioDevicePriority.pick(
      devices = devices,
      preferredDeviceId = id,
      preferredProductName = name,
      idOf = { it.id },
      nameOf = { it.name },
    )

  @Test fun pinnedIdWinsEvenWhenAnotherHeadsetIsFirstInTheList() {
    val eyewear = Device(7, "HUAWEI Eyewear 2")
    val other = Device(9, "Other Buds")
    assertEquals(eyewear, pick(listOf(other, eyewear), id = 7, name = null))
  }

  @Test fun pinnedNameWinsAfterTheDeviceIdChangesOnReconnect() {
    val eyewear = Device(42, "HUAWEI Eyewear 2")
    val other = Device(9, "Other Buds")
    assertEquals(eyewear, pick(listOf(other, eyewear), id = 7, name = "HUAWEI Eyewear 2"))
  }

  @Test fun normalizedNameMatchesCaseAndWhitespace() {
    val eyewear = Device(42, "HUAWEI Eyewear 2")
    assertEquals(eyewear, pick(listOf(eyewear), id = null, name = "  huawei eyewear 2  "))
  }

  @Test fun absentPinnedDeviceReturnsNullSoTheCallerCanFallBack() {
    val other = Device(9, "Other Buds")
    assertNull(pick(listOf(other), id = 7, name = "HUAWEI Eyewear 2"))
  }
}
