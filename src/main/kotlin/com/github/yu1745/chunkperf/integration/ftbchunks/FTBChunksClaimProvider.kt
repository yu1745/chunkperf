package com.github.yu1745.chunkperf.integration.ftbchunks

import com.github.yu1745.chunkperf.integration.ClaimInfo
import com.github.yu1745.chunkperf.integration.ClaimLookup
import com.github.yu1745.chunkperf.integration.ClaimProvider
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI
import dev.ftb.mods.ftblibrary.math.ChunkDimPos
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

class FTBChunksClaimProvider : ClaimProvider {
    override fun getTeamId(player: ServerPlayerEntity): UUID? =
        FTBTeamsAPI.api().getManager().getTeamForPlayer(player).map { it.id }.orElse(null)

    override fun getClaim(dimension: RegistryKey<World>, chunkX: Int, chunkZ: Int): ClaimLookup {
        val api = try {
            FTBChunksAPI.api()
        } catch (_: NullPointerException) {
            return ClaimLookup.Unavailable
        }
        if (!api.isManagerLoaded) return ClaimLookup.Unavailable
        val pos = ChunkDimPos(dimension, chunkX, chunkZ)
        val claimed = api.manager.getChunk(pos) ?: return ClaimLookup.Wilderness
        val team = claimed.teamData.team
        return ClaimLookup.Claimed(
            ClaimInfo(
                teamId = team.id,
                teamName = team.name.string,
                forceLoadConfigured = claimed.isForceLoaded,
                actuallyForceLoaded = api.isChunkForceLoaded(pos)
            )
        )
    }
}
