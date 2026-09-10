package com.caripc.protocol

import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.contract.ValueType
import org.junit.Assert.*
import org.junit.Test

/**
 * 线协议负载上限与空值语义（工单 P0-5 / P2-5）。
 *
 * 修复前：String 按 UTF-16 字符数判断、Bundle 完全不检查、超限抛裸 IllegalArgumentException，
 * 且 null 结果无法与「类型不匹配」区分。
 */
class IpcPayloadLimitsTest {

    @Test
    fun testOversizeStringIsRejectedWithPayloadLarge() {
        val tooLong = "a".repeat(IpcPayload.MAX_PAYLOAD_BYTES + 1)
        val error = try {
            IpcPayload.ofString(tooLong)
            null
        } catch (e: IpcError) {
            e
        }
        assertNotNull("oversize string must be rejected", error)
        assertEquals(ErrorCode.PAYLOAD_LARGE, error!!.code)
    }

    @Test
    fun testStringSizeIsMeasuredInUtf8BytesNotChars() {
        // 每个字符 3 字节：字符数只有 1/3 上限，字节数已超限
        val multiByte = "中".repeat(IpcPayload.MAX_PAYLOAD_BYTES / 3 + 1)
        assertTrue("char count is below the limit", multiByte.length <= IpcPayload.MAX_PAYLOAD_BYTES)
        val error = try {
            IpcPayload.ofString(multiByte)
            null
        } catch (e: IpcError) {
            e
        }
        assertNotNull("byte size, not char count, must be enforced", error)
        assertEquals(ErrorCode.PAYLOAD_LARGE, error!!.code)
    }

    @Test
    fun testOversizeBytesIsRejectedWithPayloadLarge() {
        val error = try {
            IpcPayload.ofBytes(ByteArray(IpcPayload.MAX_PAYLOAD_BYTES + 1))
            null
        } catch (e: IpcError) {
            e
        }
        assertEquals(ErrorCode.PAYLOAD_LARGE, error?.code)
    }

    @Test
    fun testStringWithinLimitIsAccepted() {
        val payload = IpcPayload.ofString("a".repeat(IpcPayload.MAX_PAYLOAD_BYTES))
        assertEquals(IpcPayload.MAX_PAYLOAD_BYTES, payload.byteSize())
    }

    @Test
    fun testUnsupportedValueTypeIsReportedAsInvalidArgument() {
        val error = try {
            IpcPayload.ofAny(mapOf("k" to "v"))
            null
        } catch (e: IpcError) {
            e
        }
        assertNotNull(error)
        assertEquals(ErrorCode.INVALID_ARGUMENT, error!!.code)
    }

    @Test
    fun testUnitIsEncodedAsExplicitNullPayload() {
        val payload = IpcPayload.ofAny(Unit)
        assertNotNull("Unit must map to an explicit NULL payload, not a missing one", payload)
        assertEquals(ValueType.NULL.typeTag, payload!!.typeTag)
        assertNull(payload.toValue())
        assertEquals(0, payload.byteSize())
    }

    @Test
    fun testNullValueStillMeansMissingPayload() {
        assertNull("null property value keeps meaning 'no payload'", IpcPayload.ofAny(null))
    }

    @Test
    fun testNullPayloadReportsNullTypeTag() {
        val payload = IpcPayload.ofNull()
        assertEquals(ValueType.NULL, ValueType.fromTag(payload.typeTag))
    }

    @Test
    fun testGapEnvelopeCarriesGapFromSeq() {
        val envelope = SubscriptionEnvelope(
            "sub-1",
            "inst-1",
            SubscriptionEnvelope.KIND_GAP,
            "",
            42L,
            0L,
            null,
            0,
            0L,
            null,
            null,
            0,
            false,
            null,
            41L
        )
        assertEquals(41L, envelope.gapFromSeq)
        assertEquals(SubscriptionEnvelope.KIND_GAP, envelope.kind)
    }

    @Test
    fun testLegacyEnvelopeConstructorDefaultsGapFromSeqToMinusOne() {
        val envelope = SubscriptionEnvelope(
            "sub-1", "inst-1", SubscriptionEnvelope.KIND_PROPERTY, "k", 1L, 1L,
            null, 0, 0L, null, null, 0, false, null
        )
        assertEquals(-1L, envelope.gapFromSeq)
    }
}
