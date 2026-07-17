package com.github.yu1745.chunkperf.client

import com.github.yu1745.chunkperf.ChunkPerfNetworking
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import java.util.concurrent.atomic.AtomicReference

object ChunkPerfClientState {
    val samples = AtomicReference<List<ClientChunkSample>>(emptyList())
    @Volatile var pendingTarget: PendingTarget? = null
    @Volatile var selectedIntervalTicks: Int = 100
    @Volatile var selectedClusterRadiusChunks: Int = 2
    @Volatile var selectedServerClusterRadiusChunks: Int = 2
    @Volatile var showOnlyOwnedChunks: Boolean = false
    @Volatile var showPercentage: Boolean = false
}

class ChunkPerfClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ChunkPerfNetworking.OPEN_SCREEN) { client, _, _, _ ->
            client.execute { client.setScreen(ChunkPerfScreen()) }
        }
        ClientPlayNetworking.registerGlobalReceiver(ChunkPerfNetworking.OPEN_BE_SCREEN) { client, _, _, _ ->
            client.execute { client.setScreen(BlockEntityPerfScreen()) }
        }
        ClientPlayNetworking.registerGlobalReceiver(ChunkPerfNetworking.OPEN_SERVER_SCREEN) { client, _, _, _ ->
            client.execute { client.setScreen(ServerPerfScreen()) }
        }
        ClientPlayNetworking.registerGlobalReceiver(ChunkPerfNetworking.SNAPSHOT) { client, _, buf, _ ->
            val blockEntityHotspotsEnabled = buf.readBoolean()
            val count = buf.readVarInt()
            val values = ArrayList<ClientChunkSample>(count)
            repeat(count) {
                val dimension = buf.readIdentifier()
                val chunkX = buf.readInt()
                val chunkZ = buf.readInt()
                val ownedByViewer = buf.readBoolean()
                val hasOwner = buf.readBoolean()
                val ownerTeamId = if (hasOwner) buf.readUuid() else null
                val ownerTeamName = if (hasOwner) buf.readString() else null
                val randomTickNs = buf.readLong()
                val blockEntityNs = buf.readLong()
                val entityTickNs = buf.readLong()
                val mobSpawnNs = buf.readLong()
                val blockEntityTickCount = buf.readLong()
                val entityTickCount = buf.readLong()
                val intervalTicks = buf.readVarInt()
                val hotspotCount = buf.readVarInt()
                val hotspots = ArrayList<ClientBlockEntitySample>(hotspotCount)
                repeat(hotspotCount) {
                    val pos = buf.readBlockPos()
                    val name = if (buf.readBoolean()) buf.readString() else null
                    val blockId = buf.readIdentifier()
                    val ns = buf.readLong()
                    hotspots += ClientBlockEntitySample(pos, name, blockId, ns)
                }
                values += ClientChunkSample(
                    dimension, chunkX, chunkZ, ownedByViewer, ownerTeamId, ownerTeamName, randomTickNs, blockEntityNs,
                    entityTickNs, mobSpawnNs, blockEntityTickCount, entityTickCount,
                    intervalTicks, java.util.List.copyOf(hotspots)
                )
            }
            client.execute {
                ChunkPerfClientState.samples.set(java.util.List.copyOf(values))
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val target = ChunkPerfClientState.pendingTarget ?: return@register
            val world = client.world ?: return@register
            if (world.registryKey.value == target.dimension &&
                world.isChunkLoaded(target.chunkX, target.chunkZ) &&
                client.currentScreen == null
            ) {
                client.setScreen(ChunkPerfScreen())
            }
        }
    }
}
