package com.caripc.runtime

import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.contract.NotificationPolicy
import com.caripc.contract.PropertyKey
import com.caripc.contract.Quality
import org.junit.Assert.*
import org.junit.Test

class StateStoreTest {

    @Test
    fun testRegisterAndReadSnapshot() {
        val store = StateStore("test-inst-1")
        val tempKey = PropertyKey.float("target_temp", min = 16f, max = 32f)

        store.registerProperty(tempKey)
        val initialSnap = store.readSnapshot(tempKey)

        assertEquals(Quality.UNINITIALIZED, initialSnap.quality)
        assertNull(initialSnap.value)
        assertEquals(0L, initialSnap.revision)

        // 真实更新
        val env = store.update(tempKey, 24.5f)
        assertNotNull(env)
        assertEquals(24.5f, env?.payload?.toValue())

        val snapAfter = store.readSnapshot(tempKey)
        assertEquals(Quality.VALID, snapAfter.quality)
        assertEquals(24.5f, snapAfter.value)
        assertTrue(snapAfter.revision > 0)
    }

    @Test
    fun testDeadbandFilteringUpdatesStateStoreEvenWhenFiltered() {
        val store = StateStore("test-inst-2")
        val tempKey = PropertyKey.float(
            "target_temp",
            notificationPolicy = NotificationPolicy(minNotificationIntervalMs = 0L, minDelta = 0.5)
        )
        store.registerProperty(tempKey)

        // 首次更新：24.0
        val env1 = store.update(tempKey, 24.0f)
        assertNotNull("First update must produce notification", env1)

        // 微小变化：24.2（delta = 0.2 < 0.5），应过滤通知，但 StateStore 权威值必须更新为 24.2！
        val env2 = store.update(tempKey, 24.2f)
        assertNull("Update within deadband must not produce notification", env2)

        val snap = store.readSnapshot(tempKey)
        assertEquals("StateStore authoritative value must be 24.2 even if notification was filtered", 24.2f, snap.value)

        // 累积变化：24.6（delta = 0.6 >= 0.5），必须产生通知！
        val env3 = store.update(tempKey, 24.6f)
        assertNotNull("Exceeding deadband threshold must produce notification", env3)
        assertEquals(24.6f, env3?.payload?.toValue())
    }

    @Test
    fun testConditionalWriteAndTokenAdvancement() {
        val store = StateStore("test-inst-3")
        val fanKey = PropertyKey.int("fan_speed", min = 0, max = 7)
        store.registerProperty(fanKey)

        val snap = store.readSnapshot(fanKey)
        val token = snap.writeToken
        assertNotNull(token)

        // 使用正确的 token 预占成功并返回新 token
        val newToken = store.validateAndAdvanceWriteToken(fanKey.id, token)
        assertNotEquals(token, newToken)

        // 再次使用旧 token 必须抛出 CONCURRENT_CONFLICT
        try {
            store.validateAndAdvanceWriteToken(fanKey.id, token)
            fail("Old write token must be rejected with CONCURRENT_CONFLICT")
        } catch (e: IpcError) {
            assertEquals(ErrorCode.CONCURRENT_CONFLICT, e.code)
            assertEquals(newToken, e.currentWriteToken)
        }
    }

    @Test
    fun testRangeValidation() {
        val store = StateStore("test-inst-4")
        val tempKey = PropertyKey.float("temp", min = 16f, max = 32f)

        try {
            store.update(tempKey, 10.0f)
            fail("Value below min must fail validation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("below min") == true)
        }

        try {
            store.update(tempKey, Float.NaN)
            fail("NaN must fail validation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("must be finite") == true)
        }
    }
}
