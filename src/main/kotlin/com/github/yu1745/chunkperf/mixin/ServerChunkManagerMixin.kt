package com.github.yu1745.chunkperf.mixin

import com.github.yu1745.chunkperf.ChunkPerfRuntime
import com.github.yu1745.chunkperf.sampling.SampleSource
import com.github.yu1745.chunkperf.sampling.TickSampleCollector
import com.llamalad7.mixinextras.sugar.Local
import net.minecraft.server.world.ServerChunkManager
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.chunk.WorldChunk
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ServerChunkManager::class)
abstract class ServerChunkManagerMixin {

    @Shadow
    lateinit var world: ServerWorld

    @Unique
    private var cpChunkTickStart = 0L

    @Unique
    private var cpSpawnStart = 0L

    @Inject(
        method = ["tickChunks"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;tickChunk(Lnet/minecraft/world/chunk/WorldChunk;I)V"
        )]
    )
    private fun cpBeforeTickChunk(ci: CallbackInfo) {
        if (ChunkPerfRuntime.enabled) cpChunkTickStart = System.nanoTime()
    }

    @Inject(
        method = ["tickChunks"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;tickChunk(Lnet/minecraft/world/chunk/WorldChunk;I)V",
            shift = At.Shift.AFTER
        )]
    )
    private fun cpAfterTickChunk(ci: CallbackInfo, @Local chunk: WorldChunk) {
        if (!ChunkPerfRuntime.enabled || cpChunkTickStart == 0L) return
        val elapsed = System.nanoTime() - cpChunkTickStart
        cpChunkTickStart = 0L
        ChunkPerfRuntime.safeRecord {
            TickSampleCollector.record(
                world.registryKey,
                chunk.pos.toLong(),
                SampleSource.RANDOM_TICK,
                elapsed,
                ChunkPerfRuntime.currentTick
            )
        }
    }

    @Inject(
        method = ["tickChunks"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/SpawnHelper;spawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/SpawnHelper\$Info;ZZZ)V"
        )]
    )
    private fun cpBeforeSpawn(ci: CallbackInfo) {
        if (ChunkPerfRuntime.enabled) cpSpawnStart = System.nanoTime()
    }

    @Inject(
        method = ["tickChunks"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/SpawnHelper;spawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/SpawnHelper\$Info;ZZZ)V",
            shift = At.Shift.AFTER
        )]
    )
    private fun cpAfterSpawn(ci: CallbackInfo, @Local chunk: WorldChunk) {
        if (!ChunkPerfRuntime.enabled || cpSpawnStart == 0L) return
        val elapsed = System.nanoTime() - cpSpawnStart
        cpSpawnStart = 0L
        ChunkPerfRuntime.safeRecord {
            TickSampleCollector.record(
                world.registryKey,
                chunk.pos.toLong(),
                SampleSource.MOB_SPAWN,
                elapsed,
                ChunkPerfRuntime.currentTick
            )
        }
    }
}
