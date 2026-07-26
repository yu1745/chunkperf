package com.github.yu1745.chunkperf.config

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.Logger
import java.nio.file.Files

data class ChunkPerfConfig(
    val enabled: Boolean = true,
    val snapshotIntervalTicks: Int = 20,
    val blockEntityHotspotsEnabled: Boolean = false,
    val beHotspotTopN: Int = 20,
    val beHotspotMinRecordNs: Long = 1_000L,
    val maxBeHotspotEntriesPerChunk: Int = 2_000,
    val maxGlobalBeHotspotEntries: Int = 100_000,
    val maxPublishedBeHotspots: Int = 20_000,
    val mobHotspotsEnabled: Boolean = false,
    val mobHotspotTopN: Int = 20,
    val mobHotspotMinRecordNs: Long = 1_000L,
    val maxMobHotspotEntriesPerChunk: Int = 2_000,
    val maxGlobalMobHotspotEntries: Int = 100_000,
    val maxPublishedMobHotspots: Int = 20_000,
    val maxPublishedChunks: Int = 10_000,
    val pruneStaleAfterTicks: Long = 6_000L,
    val maxTrackedChunks: Int = 100_000,
    val logTopChunks: Int = 10
) {
    fun validated(logger: Logger): ChunkPerfConfig {
        val valid = snapshotIntervalTicks >= 1 &&
            beHotspotTopN in 0..100 &&
            beHotspotMinRecordNs >= 0L &&
            maxBeHotspotEntriesPerChunk in beHotspotTopN..2_000 &&
            maxGlobalBeHotspotEntries in maxBeHotspotEntriesPerChunk..100_000 &&
            maxPublishedBeHotspots in beHotspotTopN..20_000 &&
            maxPublishedBeHotspots <= maxGlobalBeHotspotEntries &&
            mobHotspotTopN in 0..100 &&
            mobHotspotMinRecordNs >= 0L &&
            maxMobHotspotEntriesPerChunk in mobHotspotTopN..2_000 &&
            maxGlobalMobHotspotEntries in maxMobHotspotEntriesPerChunk..100_000 &&
            maxPublishedMobHotspots in mobHotspotTopN..20_000 &&
            maxPublishedMobHotspots <= maxGlobalMobHotspotEntries &&
            maxTrackedChunks in 1..100_000 &&
            maxPublishedChunks in 1..10_000 &&
            maxPublishedChunks <= maxTrackedChunks &&
            pruneStaleAfterTicks >= snapshotIntervalTicks &&
            logTopChunks in 0..100
        if (valid) return this
        logger.error("Invalid ChunkPerf configuration; using safe defaults")
        return ChunkPerfConfig()
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        fun load(logger: Logger): ChunkPerfConfig {
            val path = FabricLoader.getInstance().configDir.resolve("chunkperf.json")
            return try {
                if (!Files.exists(path)) {
                    val defaults = ChunkPerfConfig()
                    Files.createDirectories(path.parent)
                    Files.writeString(path, gson.toJson(defaults))
                    defaults
                } else {
                    gson.fromJson(Files.readString(path), ChunkPerfConfig::class.java).validated(logger)
                }
            } catch (e: Exception) {
                logger.error("Failed to load ChunkPerf configuration; using defaults", e)
                ChunkPerfConfig()
            }
        }
    }
}
