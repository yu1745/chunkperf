package com.github.yu1745.chunkperf.sampling

import net.minecraft.util.math.BlockPos

internal class ChunkTickStats(firstSeenTick: Long) {
    var randomTickNs = 0L
    var blockEntityNs = 0L
    var entityTickNs = 0L
    var mobSpawnNs = 0L
    var blockEntityTickCount = 0L
    var entityTickCount = 0L
    var lastWriteTick = firstSeenTick
    var blockEntityHotspots: HashMap<BlockPos, MutableBlockEntitySample>? = null

    fun add(source: SampleSource, ns: Long, tick: Long) {
        when (source) {
            SampleSource.RANDOM_TICK -> randomTickNs = saturatedAdd(randomTickNs, ns)
            SampleSource.BLOCK_ENTITY -> {
                blockEntityNs = saturatedAdd(blockEntityNs, ns)
                blockEntityTickCount = saturatedAdd(blockEntityTickCount, 1L)
            }
            SampleSource.ENTITY_TICK -> {
                entityTickNs = saturatedAdd(entityTickNs, ns)
                entityTickCount = saturatedAdd(entityTickCount, 1L)
            }
            SampleSource.MOB_SPAWN -> mobSpawnNs = saturatedAdd(mobSpawnNs, ns)
        }
        lastWriteTick = tick
    }
}

internal data class MutableBlockEntitySample(
    var ns: Long,
    var blockEntityName: String?
)
