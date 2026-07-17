package com.github.yu1745.chunkperf.integration

import net.minecraft.registry.RegistryKey
import net.minecraft.world.World
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

interface ClaimProvider {
    fun getClaim(dimension: RegistryKey<World>, chunkX: Int, chunkZ: Int): ClaimLookup
    fun getTeamId(player: ServerPlayerEntity): UUID? = null
}

sealed interface ClaimLookup {
    data object Wilderness : ClaimLookup
    data object Unavailable : ClaimLookup
    data class Claimed(val info: ClaimInfo) : ClaimLookup
}

data class ClaimInfo(
    val teamId: UUID,
    val teamName: String,
    val forceLoadConfigured: Boolean,
    val actuallyForceLoaded: Boolean
)

object ClaimProviderNoop : ClaimProvider {
    override fun getClaim(dimension: RegistryKey<World>, chunkX: Int, chunkZ: Int): ClaimLookup =
        ClaimLookup.Unavailable
}
