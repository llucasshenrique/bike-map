package com.ebike.router.data.dem

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads 16-bit Big-Endian elevation data from SRTM .hgt files.
 * Supports SRTM3 (1201x1201, ~90m) and SRTM1 (3601x3601, ~30m).
 */
class SrtmHgtReader(
    val tileLat: Int,
    val tileLng: Int,
    val buffer: ByteBuffer,
    val sampleSize: Int
) {
    companion object {
        const val SRTM3_SIZE = 1201
        const val SRTM1_SIZE = 3601
        const val VOID_DATA: Short = -32768

        fun fromFile(file: File, tileLat: Int, tileLng: Int): SrtmHgtReader {
            val length = file.length()
            val sampleSize = when (length) {
                1201L * 1201L * 2L -> SRTM3_SIZE
                3601L * 3601L * 2L -> SRTM1_SIZE
                else -> throw IllegalArgumentException("Unsupported HGT file size: $length bytes (expected SRTM3 2,884,802 or SRTM1 25,934,402)")
            }

            val raf = RandomAccessFile(file, "r")
            val channel = raf.channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, length)
            buffer.order(ByteOrder.BIG_ENDIAN)
            raf.close()

            return SrtmHgtReader(tileLat, tileLng, buffer, sampleSize)
        }

        fun fromByteArray(bytes: ByteArray, tileLat: Int, tileLng: Int): SrtmHgtReader {
            val sampleSize = when (bytes.size) {
                1201 * 1201 * 2 -> SRTM3_SIZE
                3601 * 3601 * 2 -> SRTM1_SIZE
                else -> throw IllegalArgumentException("Unsupported HGT byte array size: ${bytes.size}")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return SrtmHgtReader(tileLat, tileLng, buffer, sampleSize)
        }
    }

    /**
     * Checks whether coordinates are contained within this 1x1 degree tile.
     * Note: tile covers [tileLat, tileLat + 1.0] and [tileLng, tileLng + 1.0].
     */
    fun contains(lat: Double, lng: Double): Boolean {
        return lat >= tileLat && lat <= tileLat + 1.0 &&
                lng >= tileLng && lng <= tileLng + 1.0
    }

    /**
     * Get elevation in meters at specified coordinates using bilinear interpolation.
     * Returns null if coordinates are outside this tile or data is void.
     */
    fun getElevation(lat: Double, lng: Double): Double? {
        if (!contains(lat, lng)) return null

        val rowFraction = (tileLat + 1.0 - lat) * (sampleSize - 1)
        val colFraction = (lng - tileLng) * (sampleSize - 1)

        val r0 = floor(rowFraction).toInt().coerceIn(0, sampleSize - 2)
        val c0 = floor(colFraction).toInt().coerceIn(0, sampleSize - 2)
        val r1 = r0 + 1
        val c1 = c0 + 1

        val e00 = readSample(r0, c0)
        val e01 = readSample(r0, c1)
        val e10 = readSample(r1, c0)
        val e11 = readSample(r1, c1)

        // If all void, return null
        if (e00 == VOID_DATA && e01 == VOID_DATA && e10 == VOID_DATA && e11 == VOID_DATA) {
            return null
        }

        // Replace any void with nearest valid sample
        val v00 = if (e00 != VOID_DATA) e00.toDouble() else (if (e01 != VOID_DATA) e01.toDouble() else e10.toDouble())
        val v01 = if (e01 != VOID_DATA) e01.toDouble() else v00
        val v10 = if (e10 != VOID_DATA) e10.toDouble() else v00
        val v11 = if (e11 != VOID_DATA) e11.toDouble() else v01

        val dr = rowFraction - r0
        val dc = colFraction - c0

        val ele = (1.0 - dr) * (1.0 - dc) * v00 +
                (1.0 - dr) * dc * v01 +
                dr * (1.0 - dc) * v10 +
                dr * dc * v11

        return ele
    }

    fun getNearestElevation(lat: Double, lng: Double): Double? {
        if (!contains(lat, lng)) return null

        val r = ((tileLat + 1.0 - lat) * (sampleSize - 1)).roundToInt().coerceIn(0, sampleSize - 1)
        val c = ((lng - tileLng) * (sampleSize - 1)).roundToInt().coerceIn(0, sampleSize - 1)

        val sample = readSample(r, c)
        return if (sample == VOID_DATA) null else sample.toDouble()
    }

    private fun readSample(row: Int, col: Int): Short {
        val index = (row * sampleSize + col) * 2
        return buffer.getShort(index)
    }
}
