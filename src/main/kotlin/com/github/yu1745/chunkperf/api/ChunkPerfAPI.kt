package com.github.yu1745.chunkperf.api

import com.github.yu1745.chunkperf.ChunkPerfRuntime
import com.github.yu1745.chunkperf.sampling.ChunkSampleSnapshot
import net.minecraft.util.Identifier

object ChunkPerfAPI {
    @JvmStatic
    fun latestSnapshot(): List<ChunkSampleSnapshot> = ChunkPerfRuntime.latestSnapshot.get()

    @JvmStatic
    fun query(dimension: Identifier, chunkX: Int, chunkZ: Int): ChunkSampleSnapshot? =
        latestSnapshot().firstOrNull {
            it.dimension == dimension && it.chunkX == chunkX && it.chunkZ == chunkZ
        }
}
