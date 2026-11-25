package com.limelight.local.stream

import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code encoder (Byte mode, ECC Level L, Versions 1..9).
 *
 * Keeps QR generation self-contained with zero third-party dependencies for the phone-input
 * URL sheet (e.g. `http://192.168.1.15:54321/?code=4829`).
 */
class QrCodeMatrix(val size: Int, private val modules: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        // Version 1..6 specs for ECC Level L (single-block RS for V1..V5, 2 blocks for V6)
        private data class VersionSpec(
            val version: Int,
            val totalCodewords: Int,
            val ecCodewordsPerBlock: Int,
            val numBlocks: Int,
            val alignments: IntArray,
        ) {
            val size: Int get() = 17 + version * 4
            val dataCodewords: Int get() = totalCodewords - ecCodewordsPerBlock * numBlocks
            val maxByteCapacity: Int get() = dataCodewords - 2 // 4-bit mode + 8-bit count + 4-bit terminator
        }

        private val SPECS = arrayOf(
            VersionSpec(1, 26, 7, 1, intArrayOf()),
            VersionSpec(2, 44, 10, 1, intArrayOf(6, 18)),
            VersionSpec(3, 70, 15, 1, intArrayOf(6, 22)),
            VersionSpec(4, 100, 20, 1, intArrayOf(6, 26)),
            VersionSpec(5, 134, 26, 1, intArrayOf(6, 30)),
            VersionSpec(6, 172, 18, 2, intArrayOf(6, 34)),
            VersionSpec(7, 196, 20, 2, intArrayOf(6, 22, 38)),
            VersionSpec(8, 242, 24, 2, intArrayOf(6, 24, 42)),
            VersionSpec(9, 292, 30, 2, intArrayOf(6, 26, 46)),
        )

        /** Largest UTF-8 payload [encode] accepts. */
        val MAX_PAYLOAD_BYTES: Int get() = SPECS.last().maxByteCapacity

        /** Like [encode], but returns null instead of throwing when the payload does not fit. */
        fun encodeOrNull(text: String): QrCodeMatrix? =
            if (text.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) encode(text) else null

        fun encode(text: String): QrCodeMatrix {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            val spec = SPECS.firstOrNull { bytes.size <= it.maxByteCapacity }
                ?: throw IllegalArgumentException("Payload too long for QR V1..V9 (${bytes.size} bytes)")
            val data = buildDataCodewords(bytes, spec.dataCodewords)
            val finalCodewords = interleaveWithEc(data, spec)

            var bestMatrix: QrCodeMatrix? = null
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0..7) {
                val candidate = renderWithMask(spec, finalCodewords, mask)
                val score = penalty(candidate)
                if (score < bestPenalty) {
                    bestPenalty = score
                    bestMatrix = candidate
                }
            }
            return bestMatrix!!
        }

        private fun buildDataCodewords(bytes: ByteArray, capacity: Int): ByteArray {
            val bits = ArrayList<Boolean>(capacity * 8)
            fun appendBits(value: Int, count: Int) {
                for (i in count - 1 downTo 0) {
                    bits += ((value ushr i) and 1) != 0
                }
            }
            appendBits(0b0100, 4) // Byte mode
            appendBits(bytes.size, 8) // Byte count (versions 1..9)
            for (b in bytes) {
                appendBits(b.toInt() and 0xFF, 8)
            }
            // Terminator (up to 4 zero bits)
            val maxBits = capacity * 8
            val term = minOf(4, maxBits - bits.size)
            repeat(term) { bits += false }
            while (bits.size % 8 != 0) bits += false
            val out = ByteArray(capacity)
            var idx = 0
            for (i in bits.indices step 8) {
                var v = 0
                for (j in 0 until 8) {
                    v = (v shl 1) or if (bits[i + j]) 1 else 0
                }
                out[idx++] = v.toByte()
            }
            val pad = byteArrayOf(0xEC.toByte(), 0x11.toByte())
            var p = 0
            while (idx < capacity) {
                out[idx++] = pad[p and 1]
                p++
            }
            return out
        }

        private fun interleaveWithEc(data: ByteArray, spec: VersionSpec): ByteArray {
            val blockDataLen = spec.dataCodewords / spec.numBlocks
            val dataBlocks = Array(spec.numBlocks) { b ->
                data.copyOfRange(b * blockDataLen, (b + 1) * blockDataLen)
            }
            val ecBlocks = Array(spec.numBlocks) { b ->
                reedSolomonRemainder(dataBlocks[b], spec.ecCodewordsPerBlock)
            }
            val out = ByteArray(spec.totalCodewords)
            var k = 0
            for (i in 0 until blockDataLen) {
                for (b in 0 until spec.numBlocks) out[k++] = dataBlocks[b][i]
            }
            for (i in 0 until spec.ecCodewordsPerBlock) {
                for (b in 0 until spec.numBlocks) out[k++] = ecBlocks[b][i]
            }
            return out
        }

        private fun reedSolomonRemainder(data: ByteArray, degree: Int): ByteArray {
            val gen = rsGenerator(degree)
            val rem = IntArray(degree)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor rem[0]
                System.arraycopy(rem, 1, rem, 0, degree - 1)
                rem[degree - 1] = 0
                for (i in 0 until degree) {
                    rem[i] = rem[i] xor gfMul(gen[i], factor)
                }
            }
            return ByteArray(degree) { rem[it].toByte() }
        }

        private fun rsGenerator(degree: Int): IntArray {
            val coef = IntArray(degree)
            coef[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    coef[j] = gfMul(coef[j], root)
                    if (j + 1 < degree) coef[j] = coef[j] xor coef[j + 1]
                }
                root = gfMul(root, 0x02)
            }
            return coef
        }

        private fun gfMul(x: Int, y: Int): Int {
            var a = x
            var b = y
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                if (((b ushr i) and 1) != 0) z = z xor a
            }
            return z
        }

        private fun renderWithMask(spec: VersionSpec, codewords: ByteArray, mask: Int): QrCodeMatrix {
            val n = spec.size
            val mod = BooleanArray(n * n)
            val isFunc = BooleanArray(n * n)

            fun setFunc(x: Int, y: Int, dark: Boolean) {
                if (x in 0 until n && y in 0 until n) {
                    mod[y * n + x] = dark
                    isFunc[y * n + x] = true
                }
            }

            // Timing patterns
            for (i in 0 until n) {
                setFunc(6, i, i % 2 == 0)
                setFunc(i, 6, i % 2 == 0)
            }

            // Finder patterns + separators
            fun drawFinder(cx: Int, cy: Int) {
                for (dy in -4..4) {
                    for (dx in -4..4) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        val dark = dist != 2 && dist != 4
                        setFunc(cx + dx, cy + dy, dark)
                    }
                }
            }
            drawFinder(3, 3)
            drawFinder(n - 4, 3)
            drawFinder(3, n - 4)

            // Alignment patterns
            val pos = spec.alignments
            for (i in pos.indices) {
                for (j in pos.indices) {
                    if ((i == 0 && j == 0) || (i == 0 && j == pos.lastIndex) || (i == pos.lastIndex && j == 0)) {
                        continue
                    }
                    val cx = pos[i]
                    val cy = pos[j]
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            setFunc(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                        }
                    }
                }
            }

            // Reserve format info & dark module
            drawFormatBits(n, mask, ::setFunc)

            // Version information (versions 7+): 6 version bits + 12 BCH bits, placed twice
            if (spec.version >= 7) {
                var rem = spec.version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (spec.version shl 12) or rem
                for (i in 0 until 18) {
                    val dark = ((bits ushr i) and 1) != 0
                    val a = n - 11 + i % 3
                    val b = i / 3
                    setFunc(a, b, dark)
                    setFunc(b, a, dark)
                }
            }

            // Place data bits (2-column upward/downward zigzag, skipping column 6)
            var bitIdx = 0
            val totalBits = codewords.size * 8
            var upward = true
            var right = n - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until n) {
                    val y = if (upward) n - 1 - vert else vert
                    for (j in 0..1) {
                        val x = right - j
                        if (!isFunc[y * n + x]) {
                            val bit = if (bitIdx < totalBits) {
                                ((codewords[bitIdx ushr 3].toInt() ushr (7 - (bitIdx and 7))) and 1) != 0
                            } else {
                                false
                            }
                            bitIdx++
                            mod[y * n + x] = bit xor maskBit(mask, x, y)
                        }
                    }
                }
                upward = !upward
                right -= 2
            }

            return QrCodeMatrix(n, mod)
        }

        private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> (x * y) % 2 + (x * y) % 3 == 0
            6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
            7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
            else -> false
        }

        private fun drawFormatBits(n: Int, mask: Int, setFunc: (Int, Int, Boolean) -> Unit) {
            // ECC Level L = format bits 01
            val data = (0b01 shl 3) or mask
            var rem = data
            repeat(10) {
                rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            }
            val bits = ((data shl 10) or rem) xor 0x5412

            for (i in 0..5) setFunc(8, i, ((bits ushr i) and 1) != 0)
            setFunc(8, 7, ((bits ushr 6) and 1) != 0)
            setFunc(8, 8, ((bits ushr 7) and 1) != 0)
            setFunc(7, 8, ((bits ushr 8) and 1) != 0)
            for (i in 9..14) setFunc(14 - i, 8, ((bits ushr i) and 1) != 0)

            for (i in 0..7) setFunc(n - 1 - i, 8, ((bits ushr i) and 1) != 0)
            for (i in 8..14) setFunc(8, n - 15 + i, ((bits ushr i) and 1) != 0)
            setFunc(8, n - 8, true) // Dark module
        }

        private fun penalty(m: QrCodeMatrix): Int {
            val n = m.size
            var score = 0
            // Rule 1: runs of 5+ same-color modules
            for (y in 0 until n) {
                var runColor = m[0, y]
                var runLen = 1
                for (x in 1 until n) {
                    if (m[x, y] == runColor) {
                        runLen++
                        if (runLen == 5) score += 3 else if (runLen > 5) score++
                    } else {
                        runColor = m[x, y]
                        runLen = 1
                    }
                }
            }
            for (x in 0 until n) {
                var runColor = m[x, 0]
                var runLen = 1
                for (y in 1 until n) {
                    if (m[x, y] == runColor) {
                        runLen++
                        if (runLen == 5) score += 3 else if (runLen > 5) score++
                    } else {
                        runColor = m[x, y]
                        runLen = 1
                    }
                }
            }
            // Rule 2: 2x2 blocks of same color
            for (y in 0 until n - 1) {
                for (x in 0 until n - 1) {
                    val c = m[x, y]
                    if (c == m[x + 1, y] && c == m[x, y + 1] && c == m[x + 1, y + 1]) {
                        score += 3
                    }
                }
            }
            return score
        }
    }
}
