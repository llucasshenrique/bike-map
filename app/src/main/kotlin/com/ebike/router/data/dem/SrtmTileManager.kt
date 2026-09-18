package com.ebike.router.data.dem

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor

class SrtmTileManager(private val context: Context) {

    private val activeReaders = ConcurrentHashMap<String, SrtmHgtReader>()
    private val srtmDir: File = File(context.filesDir, "srtm").apply { if (!exists()) mkdirs() }

    companion object {
        fun getTileCoordinates(lat: Double, lng: Double): Pair<Int, Int> {
            val tileLat = floor(lat).toInt()
            val tileLng = floor(lng).toInt()
            return Pair(tileLat, tileLng)
        }

        fun getTileFilename(tileLat: Int, tileLng: Int): String {
            val latPrefix = if (tileLat >= 0) "N" else "S"
            val latStr = String.format("%02d", abs(tileLat))
            val lngPrefix = if (tileLng >= 0) "E" else "W"
            val lngStr = String.format("%03d", abs(tileLng))
            return "$latPrefix$latStr$lngPrefix$lngStr.hgt"
        }
    }

    fun getElevation(lat: Double, lng: Double): Double? {
        val reader = getReaderFor(lat, lng) ?: return null
        return reader.getElevation(lat, lng)
    }

    fun getReaderFor(lat: Double, lng: Double): SrtmHgtReader? {
        val (tileLat, tileLng) = getTileCoordinates(lat, lng)
        val filename = getTileFilename(tileLat, tileLng)

        activeReaders[filename]?.let { return it }

        // 1. Check local filesDir
        val localFile = File(srtmDir, filename)
        if (localFile.exists() && localFile.length() > 0) {
            try {
                val reader = SrtmHgtReader.fromFile(localFile, tileLat, tileLng)
                activeReaders[filename] = reader
                return reader
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Check Android assets/dem/
        try {
            val assetPath = "dem/$filename"
            context.assets.open(assetPath).use { input ->
                val bytes = input.readBytes()
                if (bytes.isNotEmpty()) {
                    // Cache to filesDir for memory mapping next time
                    try {
                        FileOutputStream(localFile).use { out -> out.write(bytes) }
                        val reader = SrtmHgtReader.fromFile(localFile, tileLat, tileLng)
                        activeReaders[filename] = reader
                        return reader
                    } catch (_: Exception) {
                        val reader = SrtmHgtReader.fromByteArray(bytes, tileLat, tileLng)
                        activeReaders[filename] = reader
                        return reader
                    }
                }
            }
        } catch (_: Exception) {
            // Asset not bundled
        }

        return null
    }

    fun hasTile(lat: Double, lng: Double): Boolean {
        val (tileLat, tileLng) = getTileCoordinates(lat, lng)
        val filename = getTileFilename(tileLat, tileLng)
        val localFile = File(srtmDir, filename)
        if (localFile.exists() && localFile.length() > 0) return true
        return try {
            context.assets.open("dem/$filename").close()
            true
        } catch (_: Exception) {
            false
        }
    }
}
