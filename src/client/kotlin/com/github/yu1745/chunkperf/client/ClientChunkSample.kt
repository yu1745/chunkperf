package com.github.yu1745.chunkperf.client

import net.minecraft.util.Identifier
import java.util.UUID

data class ClientChunkSample(
    val dimension: Identifier,
    val chunkX: Int,
    val chunkZ: Int,
    val ownedByViewer: Boolean,
    val ownerTeamId: UUID?,
    val ownerTeamName: String?,
    val randomTickNs: Long,
    val blockEntityNs: Long,
    val entityTickNs: Long,
    val mobSpawnNs: Long,
    val blockEntityTickCount: Long,
    val entityTickCount: Long,
    val intervalTicks: Int,
    val blockEntityHotspots: List<ClientBlockEntitySample>
    ,val mobHotspots: List<ClientMobSample>
) {
    val totalNs: Long get() = randomTickNs + blockEntityNs + entityTickNs + mobSpawnNs
    val msPerTick: Double get() = totalNs / 1_000_000.0 / intervalTicks.coerceAtLeast(1)
}

data class ClientBlockEntitySample(
    val pos: net.minecraft.util.math.BlockPos,
    val name: String?,
    val blockId: Identifier,
    val ns: Long
)

data class ClientMobSample(val pos: net.minecraft.util.math.BlockPos, val name: String, val entityId: Identifier, val ns: Long)

data class PendingTarget(val dimension: Identifier, val chunkX: Int, val chunkZ: Int)
