package com.winlator.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [ProcessHelper.getAffinityMask].
 *
 * These guard against two historical bugs:
 * - masks were built with `(int) Math.pow(2, i)`, which saturates at
 *   Integer.MAX_VALUE for core 31 and corrupted the whole mask;
 * - call sites truncated masks with `.toShort()`, sign-extending bit 15
 *   into bits 16-31.
 */
class ProcessHelperAffinityTest {

    @Test
    fun `mask from cpu list sets one bit per core`() {
        assertEquals(0b1111, ProcessHelper.getAffinityMask("0,1,2,3"))
        assertEquals(0b1, ProcessHelper.getAffinityMask("0"))
        assertEquals((1 shl 7) or (1 shl 2), ProcessHelper.getAffinityMask("2,7"))
    }

    @Test
    fun `core 15 stays a plain bit without sign extension`() {
        assertEquals(0x8000, ProcessHelper.getAffinityMask("15"))
    }

    @Test
    fun `core 31 maps to the top bit instead of saturating`() {
        // (int) Math.pow(2, 31) used to yield Integer.MAX_VALUE (0x7FFFFFFF),
        // silently enabling cores 0-30 instead of core 31.
        assertEquals(1 shl 31, ProcessHelper.getAffinityMask("31"))
        assertEquals((1 shl 31) or 1, ProcessHelper.getAffinityMask("0,31"))
    }

    @Test
    fun `empty or null list yields empty mask`() {
        assertEquals(0, ProcessHelper.getAffinityMask(""))
        assertEquals(0, ProcessHelper.getAffinityMask(null as String?))
    }

    @Test
    fun `out of range cores are ignored`() {
        assertEquals(0b1, ProcessHelper.getAffinityMask("0,32"))
        assertEquals(0, ProcessHelper.getAffinityMask("-1,40"))
    }

    @Test
    fun `whitespace around entries is tolerated`() {
        assertEquals(0b11, ProcessHelper.getAffinityMask("0, 1"))
    }

    @Test
    fun `boolean array overload matches list overload`() {
        val cpus = BooleanArray(32)
        cpus[0] = true
        cpus[15] = true
        cpus[31] = true
        assertEquals(
            ProcessHelper.getAffinityMask("0,15,31"),
            ProcessHelper.getAffinityMask(cpus),
        )
    }

    @Test
    fun `boolean array longer than 32 entries does not overflow`() {
        val cpus = BooleanArray(40) { true }
        assertEquals(-1, ProcessHelper.getAffinityMask(cpus)) // all 32 bits set
    }

    @Test
    fun `range overload covers from inclusive to exclusive`() {
        assertEquals(0b1111, ProcessHelper.getAffinityMask(0, 4))
        assertEquals(0b1100, ProcessHelper.getAffinityMask(2, 4))
        assertEquals(0, ProcessHelper.getAffinityMask(4, 4))
    }

    @Test
    fun `range overload clamps negative from and large to`() {
        assertEquals(0b11, ProcessHelper.getAffinityMask(-2, 2))
        assertEquals(-1, ProcessHelper.getAffinityMask(0, 64)) // all 32 bits set
    }

    @Test
    fun `hex string mirrors mask value`() {
        assertEquals("3", ProcessHelper.getAffinityMaskAsHexString("0,1"))
        assertEquals("8000", ProcessHelper.getAffinityMaskAsHexString("15"))
    }
}
