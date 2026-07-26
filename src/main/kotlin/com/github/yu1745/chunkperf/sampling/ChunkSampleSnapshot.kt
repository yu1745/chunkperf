package com.github.yu1745.chunkperf.sampling

import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import java.util.UUID

sealed interface ClaimSnapshot {
    data object Wilderness : ClaimSnapshot
    data object Unavailable : ClaimSnapshot
    data class Claimed(
        val teamId: UUID,
        val teamName: String,
        val forceLoadConfigured: Boolean,
        val actuallyForceLoaded: Boolean
    ) : ClaimSnapshot
}

data class BlockPosSample(
    val pos: BlockPos,
    val ns: Long,
    val blockEntityName: String?
)

data class MobSample(val pos: BlockPos, val ns: Long, val mobName: String, val entityId: Identifier)

data class ChunkSampleSnapshot(
    val dimension: Identifier,
    val chunkX: Int,
    val chunkZ: Int,
    val claim: ClaimSnapshot,
    val randomTickNs: Long,
    val blockEntityNs: Long,
    val entityTickNs: Long,
    val mobSpawnNs: Long,
    val blockEntityTickCount: Long,
    val entityTickCount: Long,
    val intervalTicks: Int,
    val blockEntityHotspots: List<BlockPosSample>,
    val mobHotspots: List<MobSample> = emptyList()
) {
    val totalNs: Long
        get() = saturatedSum(randomTickNs, blockEntityNs, entityTickNs, mobSpawnNs)

    val totalMsPerTick: Double
        get() = totalNs / 1_000_000.0 / intervalTicks
}

internal fun saturatedAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

internal fun saturatedSum(vararg values: Long): Long {
    var result = 0L
    for (value in values) result = saturatedAdd(result, value)
    return result
}
