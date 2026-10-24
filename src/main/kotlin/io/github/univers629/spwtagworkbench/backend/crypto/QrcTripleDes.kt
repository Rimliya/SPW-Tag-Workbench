// SPDX-FileCopyrightText: 2026 salt-workshop contributors
// SPDX-License-Identifier: GPL-3.0-only

package io.github.univers629.spwtagworkbench.backend.crypto

/**
 * QQ 音乐 QRC 歌词专用的 **3DES 变体**。
 *
 * **不能用 JCE 的 `DESede/ECB` 代替。** 实测同一份载荷：
 * - JCE 标准实现 → 明文 `1b873528...`，zlib 解压报 "incorrect header check"
 * - 本实现 → 明文 `789c4d99...`，zlib 解压出正常 XML
 *
 * 差异在于位序约定：这个变体的 `bitnum` 用
 * `(b / 32) * 4 + 3 - (b % 32) / 8` 取字节，即**大端位序的逆序**，
 * 与标准 DES 的字节序不同。算法来自 QQMusicDecoder 的 C# 实现
 * （LDDC 的 `tripledes.py` 也是同一份代码的 Python 移植）。
 *
 * 纯位运算、无状态，可安全并发调用。
 */
object QrcTripleDes {
    private const val ENCRYPT = 1
    private const val DECRYPT = 0

    /** 8 个 S 盒，各 64 项。 */
    private val SBOX: Array<IntArray> =
        arrayOf(
            intArrayOf(
                14,
                4,
                13,
                1,
                2,
                15,
                11,
                8,
                3,
                10,
                6,
                12,
                5,
                9,
                0,
                7,
                0,
                15,
                7,
                4,
                14,
                2,
                13,
                1,
                10,
                6,
                12,
                11,
                9,
                5,
                3,
                8,
                4,
                1,
                14,
                8,
                13,
                6,
                2,
                11,
                15,
                12,
                9,
                7,
                3,
                10,
                5,
                0,
                15,
                12,
                8,
                2,
                4,
                9,
                1,
                7,
                5,
                11,
                3,
                14,
                10,
                0,
                6,
                13,
            ),
            intArrayOf(
                15,
                1,
                8,
                14,
                6,
                11,
                3,
                4,
                9,
                7,
                2,
                13,
                12,
                0,
                5,
                10,
                3,
                13,
                4,
                7,
                15,
                2,
                8,
                15,
                12,
                0,
                1,
                10,
                6,
                9,
                11,
                5,
                0,
                14,
                7,
                11,
                10,
                4,
                13,
                1,
                5,
                8,
                12,
                6,
                9,
                3,
                2,
                15,
                13,
                8,
                10,
                1,
                3,
                15,
                4,
                2,
                11,
                6,
                7,
                12,
                0,
                5,
                14,
                9,
            ),
            intArrayOf(
                10,
                0,
                9,
                14,
                6,
                3,
                15,
                5,
                1,
                13,
                12,
                7,
                11,
                4,
                2,
                8,
                13,
                7,
                0,
                9,
                3,
                4,
                6,
                10,
                2,
                8,
                5,
                14,
                12,
                11,
                15,
                1,
                13,
                6,
                4,
                9,
                8,
                15,
                3,
                0,
                11,
                1,
                2,
                12,
                5,
                10,
                14,
                7,
                1,
                10,
                13,
                0,
                6,
                9,
                8,
                7,
                4,
                15,
                14,
                3,
                11,
                5,
                2,
                12,
            ),
            intArrayOf(
                7,
                13,
                14,
                3,
                0,
                6,
                9,
                10,
                1,
                2,
                8,
                5,
                11,
                12,
                4,
                15,
                13,
                8,
                11,
                5,
                6,
                15,
                0,
                3,
                4,
                7,
                2,
                12,
                1,
                10,
                14,
                9,
                10,
                6,
                9,
                0,
                12,
                11,
                7,
                13,
                15,
                1,
                3,
                14,
                5,
                2,
                8,
                4,
                3,
                15,
                0,
                6,
                10,
                10,
                13,
                8,
                9,
                4,
                5,
                11,
                12,
                7,
                2,
                14,
            ),
            intArrayOf(
                2,
                12,
                4,
                1,
                7,
                10,
                11,
                6,
                8,
                5,
                3,
                15,
                13,
                0,
                14,
                9,
                14,
                11,
                2,
                12,
                4,
                7,
                13,
                1,
                5,
                0,
                15,
                10,
                3,
                9,
                8,
                6,
                4,
                2,
                1,
                11,
                10,
                13,
                7,
                8,
                15,
                9,
                12,
                5,
                6,
                3,
                0,
                14,
                11,
                8,
                12,
                7,
                1,
                14,
                2,
                13,
                6,
                15,
                0,
                9,
                10,
                4,
                5,
                3,
            ),
            intArrayOf(
                12,
                1,
                10,
                15,
                9,
                2,
                6,
                8,
                0,
                13,
                3,
                4,
                14,
                7,
                5,
                11,
                10,
                15,
                4,
                2,
                7,
                12,
                9,
                5,
                6,
                1,
                13,
                14,
                0,
                11,
                3,
                8,
                9,
                14,
                15,
                5,
                2,
                8,
                12,
                3,
                7,
                0,
                4,
                10,
                1,
                13,
                11,
                6,
                4,
                3,
                2,
                12,
                9,
                5,
                15,
                10,
                11,
                14,
                1,
                7,
                6,
                0,
                8,
                13,
            ),
            intArrayOf(
                4,
                11,
                2,
                14,
                15,
                0,
                8,
                13,
                3,
                12,
                9,
                7,
                5,
                10,
                6,
                1,
                13,
                0,
                11,
                7,
                4,
                9,
                1,
                10,
                14,
                3,
                5,
                12,
                2,
                15,
                8,
                6,
                1,
                4,
                11,
                13,
                12,
                3,
                7,
                14,
                10,
                15,
                6,
                8,
                0,
                5,
                9,
                2,
                6,
                11,
                13,
                8,
                1,
                4,
                10,
                7,
                9,
                5,
                0,
                15,
                14,
                2,
                3,
                12,
            ),
            intArrayOf(
                13,
                2,
                8,
                4,
                6,
                15,
                11,
                1,
                10,
                9,
                3,
                14,
                5,
                0,
                12,
                7,
                1,
                15,
                13,
                8,
                10,
                3,
                7,
                4,
                12,
                5,
                6,
                11,
                0,
                14,
                9,
                2,
                7,
                11,
                4,
                1,
                9,
                12,
                14,
                2,
                0,
                6,
                10,
                13,
                15,
                3,
                5,
                8,
                2,
                1,
                14,
                7,
                4,
                10,
                8,
                13,
                15,
                12,
                9,
                0,
                3,
                5,
                6,
                11,
            ),
        )

    /**
     * 从字节串提取指定**位索引**的位，左移 `c` 位。
     *
     * 注意字节索引是 `(b / 32) * 4 + 3 - (b % 32) / 8`——这与标准 DES 相反，
     * 是这套算法与 JCE 不兼容的根源。
     */
    private fun bitnum(
        a: ByteArray,
        b: Int,
        c: Int,
    ): Long {
        // 必须先 and 0xFF 转成无符号：Byte 是有符号的，
        // 负数字节直接 shr 会带符号扩展，取出的位是错的。
        val byte = a[(b / 32) * 4 + 3 - (b % 32) / 8].toInt() and 0xFF
        val bit = (byte shr (7 - b % 8)) and 1
        // 返回 Long：c 可达 31，Int 的 `1 shl 31` 会变成负数（符号位），
        // 后续 or 累加会把高位污染成 1，整个密钥调度就错了。
        return bit.toLong() shl c
    }

    /**
     * 从 Long 提取指定位（从 bit31 起算），左移 `c`。
     *
     * 参数用 Long 是因为密钥调度的 `c`/`d` 是 **28 位且左对齐到 bit31**，
     * Int 承载会在高位处丢符号位。
     */
    private fun bitnumIntr(
        a: Long,
        b: Int,
        c: Int,
    ): Int = (((a ushr (31 - b)) and 1L) shl c).toInt()

    /**
     * 从 int 提取指定位（从左端溢出），右移 `c`。
     *
     * 用 Long 做中间运算：`a shl b` 在 Int 上会丢弃溢出的高位，
     * 而 Python 的无限精度整数不会——参考实现依赖那一位，必须保住它。
     */
    private fun bitnumIntl(
        a: Long,
        b: Int,
        c: Int,
    ): Long = ((a shl b) and 0x80000000L) ushr c

    /** S 盒索引的位重组。 */
    private fun sboxBit(a: Int): Int = (a and 32) or ((a and 31) shr 1) or ((a and 1) shl 4)

    private fun initialPermutation(input: ByteArray): IntArray {
        val s0L =
            bitnum(input, 57, 31) or bitnum(input, 49, 30) or bitnum(input, 41, 29) or
                bitnum(input, 33, 28) or bitnum(input, 25, 27) or bitnum(input, 17, 26) or
                bitnum(input, 9, 25) or bitnum(input, 1, 24) or bitnum(input, 59, 23) or
                bitnum(input, 51, 22) or bitnum(input, 43, 21) or bitnum(input, 35, 20) or
                bitnum(input, 27, 19) or bitnum(input, 19, 18) or bitnum(input, 11, 17) or
                bitnum(input, 3, 16) or bitnum(input, 61, 15) or bitnum(input, 53, 14) or
                bitnum(input, 45, 13) or bitnum(input, 37, 12) or bitnum(input, 29, 11) or
                bitnum(input, 21, 10) or bitnum(input, 13, 9) or bitnum(input, 5, 8) or
                bitnum(input, 63, 7) or bitnum(input, 55, 6) or bitnum(input, 47, 5) or
                bitnum(input, 39, 4) or bitnum(input, 31, 3) or bitnum(input, 23, 2) or
                bitnum(input, 15, 1) or bitnum(input, 7, 0)
        val s1L =
            bitnum(input, 56, 31) or bitnum(input, 48, 30) or bitnum(input, 40, 29) or
                bitnum(input, 32, 28) or bitnum(input, 24, 27) or bitnum(input, 16, 26) or
                bitnum(input, 8, 25) or bitnum(input, 0, 24) or bitnum(input, 58, 23) or
                bitnum(input, 50, 22) or bitnum(input, 42, 21) or bitnum(input, 34, 20) or
                bitnum(input, 26, 19) or bitnum(input, 18, 18) or bitnum(input, 10, 17) or
                bitnum(input, 2, 16) or bitnum(input, 60, 15) or bitnum(input, 52, 14) or
                bitnum(input, 44, 13) or bitnum(input, 36, 12) or bitnum(input, 28, 11) or
                bitnum(input, 20, 10) or bitnum(input, 12, 9) or bitnum(input, 4, 8) or
                bitnum(input, 62, 7) or bitnum(input, 54, 6) or bitnum(input, 46, 5) or
                bitnum(input, 38, 4) or bitnum(input, 30, 3) or bitnum(input, 22, 2) or
                bitnum(input, 14, 1) or bitnum(input, 6, 0)
        return intArrayOf(s0L.toInt(), s1L.toInt())
    }

    private fun inversePermutation(
        s0: Long,
        s1: Long,
    ): ByteArray {
        val data = ByteArray(8)
        data[3] =
            (
                bitnumIntr(s1, 7, 7) or bitnumIntr(s0, 7, 6) or bitnumIntr(s1, 15, 5) or
                    bitnumIntr(s0, 15, 4) or bitnumIntr(s1, 23, 3) or bitnumIntr(s0, 23, 2) or
                    bitnumIntr(s1, 31, 1) or bitnumIntr(s0, 31, 0)
            ).toByte()
        data[2] =
            (
                bitnumIntr(s1, 6, 7) or bitnumIntr(s0, 6, 6) or bitnumIntr(s1, 14, 5) or
                    bitnumIntr(s0, 14, 4) or bitnumIntr(s1, 22, 3) or bitnumIntr(s0, 22, 2) or
                    bitnumIntr(s1, 30, 1) or bitnumIntr(s0, 30, 0)
            ).toByte()
        data[1] =
            (
                bitnumIntr(s1, 5, 7) or bitnumIntr(s0, 5, 6) or bitnumIntr(s1, 13, 5) or
                    bitnumIntr(s0, 13, 4) or bitnumIntr(s1, 21, 3) or bitnumIntr(s0, 21, 2) or
                    bitnumIntr(s1, 29, 1) or bitnumIntr(s0, 29, 0)
            ).toByte()
        data[0] =
            (
                bitnumIntr(s1, 4, 7) or bitnumIntr(s0, 4, 6) or bitnumIntr(s1, 12, 5) or
                    bitnumIntr(s0, 12, 4) or bitnumIntr(s1, 20, 3) or bitnumIntr(s0, 20, 2) or
                    bitnumIntr(s1, 28, 1) or bitnumIntr(s0, 28, 0)
            ).toByte()
        data[7] =
            (
                bitnumIntr(s1, 3, 7) or bitnumIntr(s0, 3, 6) or bitnumIntr(s1, 11, 5) or
                    bitnumIntr(s0, 11, 4) or bitnumIntr(s1, 19, 3) or bitnumIntr(s0, 19, 2) or
                    bitnumIntr(s1, 27, 1) or bitnumIntr(s0, 27, 0)
            ).toByte()
        data[6] =
            (
                bitnumIntr(s1, 2, 7) or bitnumIntr(s0, 2, 6) or bitnumIntr(s1, 10, 5) or
                    bitnumIntr(s0, 10, 4) or bitnumIntr(s1, 18, 3) or bitnumIntr(s0, 18, 2) or
                    bitnumIntr(s1, 26, 1) or bitnumIntr(s0, 26, 0)
            ).toByte()
        data[5] =
            (
                bitnumIntr(s1, 1, 7) or bitnumIntr(s0, 1, 6) or bitnumIntr(s1, 9, 5) or
                    bitnumIntr(s0, 9, 4) or bitnumIntr(s1, 17, 3) or bitnumIntr(s0, 17, 2) or
                    bitnumIntr(s1, 25, 1) or bitnumIntr(s0, 25, 0)
            ).toByte()
        data[4] =
            (
                bitnumIntr(s1, 0, 7) or bitnumIntr(s0, 0, 6) or bitnumIntr(s1, 8, 5) or
                    bitnumIntr(s0, 8, 4) or bitnumIntr(s1, 16, 3) or bitnumIntr(s0, 16, 2) or
                    bitnumIntr(s1, 24, 1) or bitnumIntr(s0, 24, 0)
            ).toByte()
        return data
    }

    private fun f(
        state: Long,
        key: IntArray,
    ): Long {
        val t1 = (
            bitnumIntl(state, 31, 0) or ((state and 0xf0000000L) ushr 1) or
                bitnumIntl(state, 4, 5) or bitnumIntl(state, 3, 6) or
                ((state and 0x0f000000L) ushr 3) or bitnumIntl(state, 8, 11) or
                bitnumIntl(state, 7, 12) or ((state and 0x00f00000L) ushr 5) or
                bitnumIntl(state, 12, 17) or bitnumIntl(state, 11, 18) or
                ((state and 0x000f0000L) ushr 7) or bitnumIntl(state, 16, 23)
        ) and 0xFFFFFFFFL
        val t2 = (
            bitnumIntl(state, 15, 0) or ((state and 0x0000f000L) shl 15) or
                bitnumIntl(state, 20, 5) or bitnumIntl(state, 19, 6) or
                ((state and 0x00000f00L) shl 13) or bitnumIntl(state, 24, 11) or
                bitnumIntl(state, 23, 12) or ((state and 0x000000f0L) shl 11) or
                bitnumIntl(state, 28, 17) or bitnumIntl(state, 27, 18) or
                ((state and 0x0000000fL) shl 9) or bitnumIntl(state, 0, 23)
        ) and 0xFFFFFFFFL

        val lrg =
            longArrayOf(
                (t1 shr 24) and 0xff,
                (t1 shr 16) and 0xff,
                (t1 shr 8) and 0xff,
                (t2 shr 24) and 0xff,
                (t2 shr 16) and 0xff,
                (t2 shr 8) and 0xff,
            )
        for (i in 0 until 6) lrg[i] = lrg[i] xor key[i].toLong()

        val r =
            (SBOX[0][sboxBit((lrg[0] shr 2).toInt())].toLong() shl 28) or
                (SBOX[1][sboxBit((((lrg[0] and 0x03) shl 4) or (lrg[1] shr 4)).toInt())].toLong() shl 24) or
                (SBOX[2][sboxBit((((lrg[1] and 0x0f) shl 2) or (lrg[2] shr 6)).toInt())].toLong() shl 20) or
                (SBOX[3][sboxBit((lrg[2] and 0x3f).toInt())].toLong() shl 16) or
                (SBOX[4][sboxBit((lrg[3] shr 2).toInt())].toLong() shl 12) or
                (SBOX[5][sboxBit((((lrg[3] and 0x03) shl 4) or (lrg[4] shr 4)).toInt())].toLong() shl 8) or
                (SBOX[6][sboxBit((((lrg[4] and 0x0f) shl 2) or (lrg[5] shr 6)).toInt())].toLong() shl 4) or
                SBOX[7][sboxBit((lrg[5] and 0x3f).toInt())].toLong()

        return (
            bitnumIntl(r, 15, 0) or bitnumIntl(r, 6, 1) or bitnumIntl(r, 19, 2) or
                bitnumIntl(r, 20, 3) or bitnumIntl(r, 28, 4) or bitnumIntl(r, 11, 5) or
                bitnumIntl(r, 27, 6) or bitnumIntl(r, 16, 7) or bitnumIntl(r, 0, 8) or
                bitnumIntl(r, 14, 9) or bitnumIntl(r, 22, 10) or bitnumIntl(r, 25, 11) or
                bitnumIntl(r, 4, 12) or bitnumIntl(r, 17, 13) or bitnumIntl(r, 30, 14) or
                bitnumIntl(r, 9, 15) or bitnumIntl(r, 1, 16) or bitnumIntl(r, 7, 17) or
                bitnumIntl(r, 23, 18) or bitnumIntl(r, 13, 19) or bitnumIntl(r, 31, 20) or
                bitnumIntl(r, 26, 21) or bitnumIntl(r, 2, 22) or bitnumIntl(r, 8, 23) or
                bitnumIntl(r, 18, 24) or bitnumIntl(r, 12, 25) or bitnumIntl(r, 29, 26) or
                bitnumIntl(r, 5, 27) or bitnumIntl(r, 21, 28) or bitnumIntl(r, 10, 29) or
                bitnumIntl(r, 3, 30) or bitnumIntl(r, 24, 31)
        ) and 0xFFFFFFFFL
    }

    private fun crypt(
        input: ByteArray,
        key: Array<IntArray>,
    ): ByteArray {
        val ip = initialPermutation(input)
        // **必须 and 0xFFFFFFFFL**：Int 的 toLong() 会做符号扩展，
        // 负的 Int 变成 64 位负数（高 32 位全是 1），参与 xor 后整块就错了。
        // 实测表现为「第一块对、后续块全错」——因为块 0 的高位恰好没被用到。
        var s0 = ip[0].toLong() and 0xFFFFFFFFL
        var s1 = ip[1].toLong() and 0xFFFFFFFFL
        for (idx in 0 until 15) {
            val prev = s1
            s1 = f(s1, key[idx]) xor s0
            s0 = prev
        }
        s0 = f(s1, key[15]) xor s0
        return inversePermutation(s0 and 0xFFFFFFFFL, s1 and 0xFFFFFFFFL)
    }

    private fun keySchedule(
        key: ByteArray,
        mode: Int,
    ): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }
        val rndShift = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
        val permC =
            intArrayOf(
                56,
                48,
                40,
                32,
                24,
                16,
                8,
                0,
                57,
                49,
                41,
                33,
                25,
                17,
                9,
                1,
                58,
                50,
                42,
                34,
                26,
                18,
                10,
                2,
                59,
                51,
                43,
                35,
            )
        val permD =
            intArrayOf(
                62,
                54,
                46,
                38,
                30,
                22,
                14,
                6,
                61,
                53,
                45,
                37,
                29,
                21,
                13,
                5,
                60,
                52,
                44,
                36,
                28,
                20,
                12,
                4,
                27,
                19,
                11,
                3,
            )
        val compression =
            intArrayOf(
                13,
                16,
                10,
                23,
                0,
                4,
                2,
                27,
                14,
                5,
                20,
                9,
                22,
                18,
                11,
                3,
                25,
                7,
                15,
                6,
                26,
                19,
                12,
                1,
                40,
                51,
                30,
                36,
                46,
                54,
                29,
                39,
                50,
                44,
                32,
                47,
                43,
                48,
                38,
                55,
                33,
                52,
                45,
                41,
                49,
                35,
                28,
                31,
            )

        // 用 Long 累加：bitnum 的某个返回值可能带第 31 位（负数），
        // Int 的 or 拼接会污染符号位，Long 不会。
        var c = 0L
        var d = 0L
        // c/d 是 **28 位、但左对齐到 bit31**（bitnum 的 c 参数从 31 递减到 4）。
        // 因此掩码只能是 0xfffffff0（清低 4 位），**不能**再用 28 位掩码把 bit28-31 抹掉——
        // 那会把 c 的高 4 位清零，bitnumIntr 就取不到位（实测 schedule 全为 0）。
        for (i in 0 until 28) c = c or bitnum(key, permC[i], 31 - i)

        for (i in 0 until 28) d = d or bitnum(key, permD[i], 31 - i)

        for (i in 0 until 16) {
            // 28 位循环左移，全程用 Long
            c = ((c shl rndShift[i]) or (c ushr (28 - rndShift[i]))) and 0xfffffff0L
            d = ((d shl rndShift[i]) or (d ushr (28 - rndShift[i]))) and 0xfffffff0L
            val togen = if (mode == DECRYPT) 15 - i else i
            for (j in 0 until 6) schedule[togen][j] = 0
            for (j in 0 until 24) {
                schedule[togen][j / 8] = schedule[togen][j / 8] or bitnumIntr(c, compression[j], 7 - (j % 8))
            }
            for (j in 24 until 48) {
                schedule[togen][j / 8] =
                    schedule[togen][j / 8] or bitnumIntr(d, compression[j] - 27, 7 - (j % 8))
            }
        }
        return schedule
    }

    private fun keySetup(
        key: ByteArray,
        mode: Int,
    ): Array<Array<IntArray>> =
        if (mode == ENCRYPT) {
            arrayOf(
                keySchedule(key.copyOfRange(0, 8), ENCRYPT),
                keySchedule(key.copyOfRange(8, 16), DECRYPT),
                keySchedule(key.copyOfRange(16, 24), ENCRYPT),
            )
        } else {
            arrayOf(
                keySchedule(key.copyOfRange(16, 24), DECRYPT),
                keySchedule(key.copyOfRange(8, 16), ENCRYPT),
                keySchedule(key.copyOfRange(0, 8), DECRYPT),
            )
        }

    /**
     * 3DES 解密。输入长度必须是 8 的倍数（QRC 载荷总是满足）。
     *
     * @param data 密文
     * @param key 24 字节密钥
     */
    fun decrypt(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        require(key.size >= 24) { "3DES 密钥需要 24 字节，实际 ${key.size}" }
        // 3DES = 三层 DES 依次作用：EDE 形式在 keySetup 里已按 DECRYPT 模式排好序
        val schedule = keySetup(key.copyOfRange(0, 24), DECRYPT)
        val out = ByteArray(data.size)
        var offset = 0
        while (offset + 8 <= data.size) {
            var block = data.copyOfRange(offset, offset + 8)
            for (layer in 0 until 3) {
                block = crypt(block, schedule[layer])
            }
            System.arraycopy(block, 0, out, offset, 8)
            offset += 8
        }
        return out
    }
}
