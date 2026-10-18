package com.bonsaponsi.baptv

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Minimal QR code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1–10
 * (up to 213 bytes – enough for links). Used for report and issue links shown on the TV screen.
 */
class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {

    operator fun get(x: Int, y: Int) = modules[y][x]

    /** Black-on-white bitmap with a 4-module quiet zone, [scale] pixels per module. */
    fun toBitmap(scale: Int): Bitmap {
        val border = 4
        val side = (size + 2 * border) * scale
        val pixels = IntArray(side * side) { Color.WHITE }
        for (y in 0 until size) for (x in 0 until size) {
            if (!modules[y][x]) continue
            for (dy in 0 until scale) {
                val row = ((y + border) * scale + dy) * side
                for (dx in 0 until scale) pixels[row + (x + border) * scale + dx] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    companion object {
        // Level M, versions 1..10: error correction codewords per block, and block counts (group 1, group 2).
        private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = arrayOf(
            intArrayOf(1, 0), intArrayOf(1, 0), intArrayOf(1, 0), intArrayOf(2, 0), intArrayOf(2, 0),
            intArrayOf(4, 0), intArrayOf(4, 0), intArrayOf(2, 2), intArrayOf(3, 2), intArrayOf(4, 1),
        )
        private val ALIGNMENT = arrayOf(
            intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
            intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        )
        private const val MAX_VERSION = 10

        /** Encodes [text] as UTF-8; null when it is too long for version 10. */
        fun encode(text: String): QrCode? {
            val data = text.toByteArray(Charsets.UTF_8)
            for (version in 1..MAX_VERSION) {
                val capacity = dataCodewords(version)
                val countBits = if (version < 10) 8 else 16
                if (4 + countBits + 8 * data.size <= capacity * 8) return build(version, data, countBits)
            }
            return null
        }

        private fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun totalBlocks(version: Int) = BLOCKS[version - 1][0] + BLOCKS[version - 1][1]

        private fun dataCodewords(version: Int) =
            rawModules(version) / 8 - ECC_PER_BLOCK[version - 1] * totalBlocks(version)

        private fun build(version: Int, data: ByteArray, countBits: Int): QrCode {
            // Data bits: mode 0100 (byte), length, data, terminator, padding.
            val capacity = dataCodewords(version)
            val bits = ArrayList<Boolean>()
            fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
            put(0b0100, 4)
            put(data.size, countBits)
            data.forEach { put(it.toInt() and 0xFF, 8) }
            put(0, minOf(4, capacity * 8 - bits.size))
            while (bits.size % 8 != 0) bits += false
            val codewords = ByteArray(capacity)
            for (i in 0 until bits.size / 8) {
                var b = 0
                for (j in 0 until 8) b = (b shl 1) or (if (bits[i * 8 + j]) 1 else 0)
                codewords[i] = b.toByte()
            }
            var pad = 0xEC
            for (i in bits.size / 8 until capacity) { codewords[i] = pad.toByte(); pad = pad xor (0xEC xor 0x11) }

            val qr = Builder(version)
            qr.drawFunctionPatterns()
            qr.drawCodewords(interleave(version, codewords))
            return qr.applyBestMask()
        }

        /** Splits data into blocks, adds Reed–Solomon codewords and interleaves them. */
        private fun interleave(version: Int, data: ByteArray): ByteArray {
            val eccLen = ECC_PER_BLOCK[version - 1]
            val blocks = totalBlocks(version)
            val shortBlocks = blocks - rawModules(version) / 8 % blocks
            val shortLen = rawModules(version) / 8 / blocks
            val divisor = rsDivisor(eccLen)
            val out = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until blocks) {
                val len = shortLen - eccLen + if (i < shortBlocks) 0 else 1
                val dat = data.copyOfRange(k, k + len)
                k += len
                val ecc = rsRemainder(dat, divisor)
                // Short blocks get a placeholder so all blocks interleave column by column.
                val block = if (i < shortBlocks) dat + byteArrayOf(0) + ecc else dat + ecc
                out += block
            }
            val result = ByteArray(rawModules(version) / 8)
            var n = 0
            for (i in out[0].indices) for (j in out.indices) {
                if (i != shortLen - eccLen || j >= shortBlocks) result[n++] = out[j][i]
            }
            return result
        }

        private fun rsMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree).also { it[degree - 1] = 1 }
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = rsMultiply(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = rsMultiply(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor rsMultiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }
    }

    private class Builder(val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; function[y][x] = true }

        fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val align = ALIGNMENT[version - 1]
            for (i in align.indices) for (j in align.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == align.size - 1) || (i == align.size - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            drawFormat(0)
            drawVersion()
        }

        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val d = maxOf(Math.abs(dx), Math.abs(dy))
                    set(x, y, d != 2 && d != 4)
                }
            }
        }

        fun drawFormat(mask: Int) {
            val data = (0 shl 3) or mask // level M = 00
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark); set(b, a, dark)
            }
        }

        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!function[y][x] && i < data.size * 8) {
                        modules[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (function[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) modules[y][x] = !modules[y][x]
            }
        }

        fun applyBestMask(): QrCode {
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask); drawFormat(mask)
                val p = penalty()
                if (p < bestPenalty) { bestPenalty = p; best = mask }
                applyMask(mask) // XOR undoes the mask
            }
            applyMask(best); drawFormat(best)
            return QrCode(size, modules)
        }

        /** Mask penalty (runs, 2×2 blocks, finder-like patterns, dark balance) – any mask is valid, this picks a good one. */
        fun penalty(): Int {
            var result = 0
            // Finder-like 1:1:3:1:1 patterns with 4 light modules on one side confuse readers.
            val finderA = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
            val finderB = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
            for (horizontal in listOf(true, false)) for (a in 0 until size) for (start in 0..size - 11) {
                var matchA = true
                var matchB = true
                for (k in 0 until 11) {
                    val m = if (horizontal) modules[a][start + k] else modules[start + k][a]
                    if (m != finderA[k]) matchA = false
                    if (m != finderB[k]) matchB = false
                }
                if (matchA) result += 40
                if (matchB) result += 40
            }
            for (horizontal in listOf(true, false)) for (a in 0 until size) {
                var run = 1
                for (b in 1 until size) {
                    val cur = if (horizontal) modules[a][b] else modules[b][a]
                    val prev = if (horizontal) modules[a][b - 1] else modules[b - 1][a]
                    if (cur == prev) { run++; if (run == 5) result += 3 else if (run > 5) result++ } else run = 1
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            result += (Math.abs(dark * 20 - total * 10) + total - 1) / total * 10
            return result
        }
    }
}
