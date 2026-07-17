package com.github.yu1745.chunkperf.integration

import com.github.yu1745.chunkperf.ChunkPerfRuntime
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

object ClaimRegistry {
    @Volatile
    var provider: ClaimProvider = ClaimProviderNoop
        private set

    @Volatile
    private var providerFailed = false

    fun install(provider: ClaimProvider) {
        this.provider = provider
        providerFailed = false
    }

    fun safeGetClaim(dimension: RegistryKey<World>, chunkX: Int, chunkZ: Int): ClaimLookup {
        if (providerFailed) return ClaimLookup.Unavailable
        return try {
            provider.getClaim(dimension, chunkX, chunkZ)
        } catch (e: Exception) {
            disableProvider(e)
            ClaimLookup.Unavailable
        } catch (e: LinkageError) {
            disableProvider(e)
            ClaimLookup.Unavailable
        }
    }

    fun safeGetTeamId(player: ServerPlayerEntity): UUID? {
        if (providerFailed) return null
        return try {
            provider.getTeamId(player)
        } catch (e: Exception) {
            disableProvider(e)
            null
        } catch (e: LinkageError) {
            disableProvider(e)
            null
        }
    }

    fun reset() {
        provider = ClaimProviderNoop
        providerFailed = false
    }

    private fun disableProvider(error: Throwable) {
        if (!providerFailed) ChunkPerfRuntime.logger.error("FTB Chunks integration failed; disabling it", error)
        providerFailed = true
        provider = ClaimProviderNoop
    }
}
