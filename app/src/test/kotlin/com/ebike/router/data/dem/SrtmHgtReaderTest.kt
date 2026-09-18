package com.ebike.router.data.dem

import org.junit.Assert.*
import org.junit.Test

class SrtmHgtReaderTest {

    /** Builds a raw SRTM3 (1201x1201, big-endian 16-bit) byte array via [elevationAt]. */
    private fun buildTile(elevationAt: (row: Int, col: Int) -> Short): ByteArray {
        val size = SrtmHgtReader.SRTM3_SIZE
        val bytes = ByteArray(size * size * 2)
        for (row in 0 until size) {
            for (col in 0 until size) {
                val value = elevationAt(row, col)
                val index = (row * size + col) * 2
                bytes[index] = (value.toInt() shr 8 and 0xFF).toByte()
                bytes[index + 1] = (value.toInt() and 0xFF).toByte()
            }
        }
        return bytes
    }

    @Test
    fun testFromByteArrayRejectsUnsupportedSize() {
        assertThrows(IllegalArgumentException::class.java) {
            SrtmHgtReader.fromByteArray(ByteArray(10), tileLat = 0, tileLng = 0)
        }
    }

    @Test
    fun testContainsBoundaries() {
        val reader = SrtmHgtReader.fromByteArray(buildTile { _, _ -> 100 }, tileLat = -23, tileLng = -47)
        assertTrue(reader.contains(-23.0, -47.0))
        assertTrue(reader.contains(-22.0, -46.0))
        assertTrue(reader.contains(-22.5, -46.5))
        assertFalse(reader.contains(-21.9, -46.5))
        assertFalse(reader.contains(-22.5, -45.9))
    }

    @Test
    fun testGetElevationOnConstantTileReturnsConstant() {
        val reader = SrtmHgtReader.fromByteArray(buildTile { _, _ -> 500 }, tileLat = 0, tileLng = 0)
        assertEquals(500.0, reader.getElevation(0.5, 0.5)!!, 0.001)
        assertEquals(500.0, reader.getElevation(0.0, 0.0)!!, 0.001)
        assertEquals(500.0, reader.getElevation(1.0, 1.0)!!, 0.001)
    }

    @Test
    fun testGetElevationOutsideTileReturnsNull() {
        val reader = SrtmHgtReader.fromByteArray(buildTile { _, _ -> 500 }, tileLat = 0, tileLng = 0)
        assertNull(reader.getElevation(5.0, 5.0))
    }

    @Test
    fun testGetElevationAllVoidReturnsNull() {
        val reader = SrtmHgtReader.fromByteArray(
            buildTile { _, _ -> SrtmHgtReader.VOID_DATA },
            tileLat = 0,
            tileLng = 0
        )
        assertNull(reader.getElevation(0.5, 0.5))
    }

    @Test
    fun testGetElevationInterpolatesAcrossLatitudeGradient() {
        // Row increases as latitude decreases from the tile's northern edge;
        // elevation == row lets us assert bilinear interpolation directly.
        val reader = SrtmHgtReader.fromByteArray(
            buildTile { row, _ -> row.toShort() },
            tileLat = 0,
            tileLng = 0
        )
        val maxRow = (SrtmHgtReader.SRTM3_SIZE - 1).toDouble()

        // Top edge of the tile (lat = tileLat + 1.0) is row 0.
        assertEquals(0.0, reader.getElevation(1.0, 0.5)!!, 1.0)
        // Bottom edge of the tile (lat = tileLat) is the last row.
        assertEquals(maxRow, reader.getElevation(0.0, 0.5)!!, 1.0)
        // Midpoint latitude should be roughly the middle row.
        assertEquals(maxRow / 2.0, reader.getElevation(0.5, 0.5)!!, 1.0)
    }

    @Test
    fun testGetNearestElevationReturnsNullWhenVoid() {
        val reader = SrtmHgtReader.fromByteArray(
            buildTile { _, _ -> SrtmHgtReader.VOID_DATA },
            tileLat = 0,
            tileLng = 0
        )
        assertNull(reader.getNearestElevation(0.5, 0.5))
    }

    @Test
    fun testGetNearestElevationOutsideTileReturnsNull() {
        val reader = SrtmHgtReader.fromByteArray(buildTile { _, _ -> 500 }, tileLat = 0, tileLng = 0)
        assertNull(reader.getNearestElevation(10.0, 10.0))
    }
}
