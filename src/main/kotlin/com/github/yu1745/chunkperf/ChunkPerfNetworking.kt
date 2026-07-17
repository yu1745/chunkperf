package com.github.yu1745.chunkperf

import com.github.yu1745.chunkperf.sampling.ChunkSampleSnapshot
import com.github.yu1745.chunkperf.sampling.ClaimSnapshot
import com.github.yu1745.chunkperf.integration.ClaimRegistry
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.PacketByteBuf
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier

object ChunkPerfNetworking {
    val OPEN_SCREEN = Identifier("chunkperf", "open_screen")
    val OPEN_BE_SCREEN = Identifier("chunkperf", "open_be_screen")
    val SNAPSHOT = Identifier("chunkperf", "snapshot")
    val SUBSCRIBE = Identifier("chunkperf", "subscribe")
    val UNSUBSCRIBE = Identifier("chunkperf", "unsubscribe")
    val BE_SUBSCRIBE = Identifier("chunkperf", "be_subscribe")
    val BE_UNSUBSCRIBE = Identifier("chunkperf", "be_unsubscribe")
    val TELEPORT = Identifier("chunkperf", "teleport")
    val SET_INTERVAL = Identifier("chunkperf", "set_interval")

    fun sendOpen(player: ServerPlayerEntity) {
        ServerPlayNetworking.send(player, OPEN_SCREEN, PacketByteBufs.empty())
    }

    fun sendOpenBe(player: ServerPlayerEntity) {
        ServerPlayNetworking.send(player, OPEN_BE_SCREEN, PacketByteBufs.empty())
    }

    fun sendSnapshot(player: ServerPlayerEntity, snapshots: List<ChunkSampleSnapshot>, includeBlockEntities: Boolean = false) {
        val buf = PacketByteBufs.create()
        buf.writeBoolean(ChunkPerfRuntime.detailedBlockEntitySampling)
        val viewerTeamId = ClaimRegistry.safeGetTeamId(player)
        buf.writeVarInt(snapshots.size)
        for (snapshot in snapshots) writeSnapshot(buf, snapshot, includeBlockEntities, player, viewerTeamId)
        ServerPlayNetworking.send(player, SNAPSHOT, buf)
    }

    private fun writeSnapshot(buf: PacketByteBuf, value: ChunkSampleSnapshot, includeBlockEntities: Boolean, player: ServerPlayerEntity, viewerTeamId: java.util.UUID?) {
        buf.writeIdentifier(value.dimension)
        buf.writeInt(value.chunkX)
        buf.writeInt(value.chunkZ)
        buf.writeBoolean(viewerTeamId != null && value.claim is ClaimSnapshot.Claimed && value.claim.teamId == viewerTeamId)
        buf.writeLong(value.randomTickNs)
        buf.writeLong(value.blockEntityNs)
        buf.writeLong(value.entityTickNs)
        buf.writeLong(value.mobSpawnNs)
        buf.writeLong(value.blockEntityTickCount)
        buf.writeLong(value.entityTickCount)
        buf.writeVarInt(value.intervalTicks)
        val hotspots = if (includeBlockEntities) value.blockEntityHotspots else emptyList()
        buf.writeVarInt(hotspots.size)
        val world = if (includeBlockEntities) player.server.getWorld(RegistryKey.of(RegistryKeys.WORLD, value.dimension)) else null
        for (hotspot in hotspots) {
            buf.writeBlockPos(hotspot.pos)
            buf.writeBoolean(hotspot.blockEntityName != null)
            if (hotspot.blockEntityName != null) buf.writeString(hotspot.blockEntityName)
            val blockId = world?.getBlockState(hotspot.pos)?.block?.let(Registries.BLOCK::getId)
                ?: Identifier("minecraft", "air")
            buf.writeIdentifier(blockId)
            buf.writeLong(hotspot.ns)
        }
    }
}
