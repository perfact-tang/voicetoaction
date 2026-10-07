package com.vibecodingjapan.ideavox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRuntimeTest {
  @After
  fun reset() = UploadRuntime.finish()

  @Test
  fun minimizedTask_keepsReceivingProgressWithoutCancelling() {
    UploadRuntime.begin("正在转换为 M4A", "source.wav")
    UploadRuntime.minimize()
    val worker = Thread {
      repeat(10_000) { UploadRuntime.update("正在转换为 M4A", "source.wav", it % 100) }
    }
    worker.start()
    repeat(1_000) { UploadRuntime.restore(); UploadRuntime.minimize() }
    worker.join()
    assertTrue(UploadRuntime.state.value.minimized)
    assertTrue(UploadRuntime.state.value.active)
    assertEquals(99, UploadRuntime.state.value.progress)
    assertFalse(UploadRuntime.cancelRequested.get())
    UploadRuntime.restore()
    assertFalse(UploadRuntime.state.value.minimized)
    assertEquals(99, UploadRuntime.state.value.progress)
  }

  @Test
  fun failureWhileMinimized_remainsAvailableWithoutReopeningWindow() {
    UploadRuntime.begin("正在转换为 M4A", "source.wav")
    UploadRuntime.minimize()
    UploadRuntime.fail("测试失败")
    UploadRuntime.update("正在上传", "source.m4a", 90)
    assertTrue(UploadRuntime.state.value.minimized)
    assertTrue(UploadRuntime.state.value.terminal)
    assertEquals("测试失败", UploadRuntime.state.value.error)
    UploadRuntime.restore()
    assertTrue(UploadRuntime.state.value.terminal)
    assertFalse(UploadRuntime.state.value.minimized)
  }

  @Test
  fun finishAndNewTask_resetMinimizationAndCancellation() {
    UploadRuntime.begin("正在转换为 M4A", "first.wav")
    UploadRuntime.minimize()
    UploadRuntime.requestCancel()
    assertTrue(UploadRuntime.state.value.minimized)
    assertFalse(UploadRuntime.state.value.cancellable)
    UploadRuntime.finish()
    UploadRuntime.minimize()
    assertFalse(UploadRuntime.state.value.active)
    UploadRuntime.begin("正在转换为 M4A", "second.wav")
    assertFalse(UploadRuntime.state.value.minimized)
    assertTrue(UploadRuntime.state.value.cancellable)
    assertFalse(UploadRuntime.cancelRequested.get())
  }
}
