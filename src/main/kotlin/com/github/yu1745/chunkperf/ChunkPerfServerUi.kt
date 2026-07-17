package com.github.yu1745.chunkperf

import com.mojang.brigadier.Command
import com.github.yu1745.chunkperf.sampling.TickSampleCollector
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.world.Heightmap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ChunkPerfServerUi {
    private val chunkViewers = ConcurrentHashMap.newKeySet<UUID>()
    private val blockEntityViewers = ConcurrentHashMap.newKeySet<UUID>()

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                net.minecraft.server.command.CommandManager.literal("perfchunk")
                    .requires { it.entity is ServerPlayerEntity && it.hasPermissionLevel(2) }
                    .executes { context ->
                        val player = context.source.player!!
                        ChunkPerfNetworking.sendOpen(player)
                        Command.SINGLE_SUCCESS
                    }
            )
            dispatcher.register(
                net.minecraft.server.command.CommandManager.literal("perfbe")
                    .requires { it.entity is ServerPlayerEntity && it.hasPermissionLevel(2) }
                    .executes { context ->
                        val player = context.source.player!!
                        ChunkPerfNetworking.sendOpenBe(player)
                        Command.SINGLE_SUCCESS
                    }
            )
            dispatcher.register(
                net.minecraft.server.command.CommandManager.literal("perfserver")
                    .requires { it.entity is ServerPlayerEntity && it.hasPermissionLevel(2) }
                    .executes { context ->
                        val player = context.source.player!!
                        ChunkPerfNetworking.sendOpenServer(player)
                        Command.SINGLE_SUCCESS
                    }
            )
        }
        ServerPlayNetworking.registerGlobalReceiver(ChunkPerfNetworking.SUBSCRIBE) { server, player, _, _, _ ->
            server.execute {
                chunkViewers += player.uuid
                ChunkPerfNetworking.sendSnapshot(player, ChunkPerfRuntime.latestSnapshot.get())
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(ChunkPerfNetworking.UNSUBSCRIBE) { server, player, _, _, _ ->
            server.execute { chunkViewers -= player.uuid }
        }
        ServerPlayNetworking.registerGlobalReceiver(ChunkPerfNetworking.BE_SUBSCRIBE) { server, player, _, _, _ ->
            server.execute {
                if (!player.hasPermissionLevel(2)) return@execute
                blockEntityViewers += player.uuid
                ChunkPerfRuntime.setDetailedBlockEntitySampling(true)
                ChunkPerfNetworking.sendSnapshot(player, ChunkPerfRuntime.latestSnapshot.get(), true)
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(ChunkPerfNetworking.BE_UNSUBSCRIBE) { server, player, _, _, _ ->
            server.execute {
                blockEntityViewers -= player.uuid
                disableBlockEntitySamplingWhenUnused()
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(ChunkPerfNetworking.TELEPORT) { server, player, _, buf, _ ->
            val dimensionId = buf.readIdentifier()
            val chunkX = buf.readInt()
            val chunkZ = buf.readInt()
            server.execute { teleport(player, dimensionId, chunkX, chunkZ) }
        }
        ServerPlayNetworking.registerGlobalReceiver(ChunkPerfNetworking.SET_INTERVAL) { server, player, _, buf, _ ->
            val ticks = buf.readVarInt()
            server.execute {
                if (player.hasPermissionLevel(2)) {
                    ChunkPerfRuntime.snapshotIntervalTicks = ticks.coerceIn(20, 200)
                    ChunkPerfRuntime.logger.info("{} changed snapshot interval to {} ticks", player.entityName, ChunkPerfRuntime.snapshotIntervalTicks)
                }
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            chunkViewers -= handler.player.uuid
            blockEntityViewers -= handler.player.uuid
            disableBlockEntitySamplingWhenUnused()
        }
    }

    fun publish(server: MinecraftServer) {
        if (chunkViewers.isEmpty() && blockEntityViewers.isEmpty()) return
        val snapshots = ChunkPerfRuntime.latestSnapshot.get()
        for (uuid in chunkViewers.toList()) {
            val player = server.playerManager.getPlayer(uuid)
            if (player == null) chunkViewers -= uuid else ChunkPerfNetworking.sendSnapshot(player, snapshots)
        }
        for (uuid in blockEntityViewers.toList()) {
            val player = server.playerManager.getPlayer(uuid)
            if (player == null) blockEntityViewers -= uuid else ChunkPerfNetworking.sendSnapshot(player, snapshots, true)
        }
        disableBlockEntitySamplingWhenUnused()
    }

    private fun disableBlockEntitySamplingWhenUnused() {
        if (blockEntityViewers.isNotEmpty()) return
        ChunkPerfRuntime.setDetailedBlockEntitySampling(false)
        TickSampleCollector.clearBlockEntityHotspots()
    }

    private fun teleport(player: ServerPlayerEntity, dimensionId: Identifier, chunkX: Int, chunkZ: Int) {
        if (!player.hasPermissionLevel(2)) return
        val key = RegistryKey.of(RegistryKeys.WORLD, dimensionId)
        val world: ServerWorld = player.server.getWorld(key) ?: return
        val x = chunkX * 16 + 8
        val z = chunkZ * 16 + 8
        val y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) + 1
        player.teleport(world, x + 0.5, y.toDouble(), z + 0.5, player.yaw, player.pitch)
    }
}
