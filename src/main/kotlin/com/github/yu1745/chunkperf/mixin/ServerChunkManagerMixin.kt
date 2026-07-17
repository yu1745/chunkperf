package com.github.yu1745.chunkperf.mixin

import com.github.yu1745.chunkperf.ChunkPerfRuntime
import com.github.yu1745.chunkperf.sampling.SampleSource
import com.github.yu1745.chunkperf.sampling.TickSampleCollector
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import net.minecraft.server.world.ServerChunkManager
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.SpawnHelper
import net.minecraft.world.chunk.WorldChunk
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(ServerChunkManager::class)
abstract class ServerChunkManagerMixin {
    @WrapOperation(
        method = ["tickChunks"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;tickChunk(Lnet/minecraft/world/chunk/WorldChunk;I)V"
        )]
    )
    private fun measureTickChunk(
        world: ServerWorld,
        chunk: WorldChunk,
        randomTickSpeed: Int,
        operation: Operation<Void>
    ) {
        if (!ChunkPerfRuntime.enabled) {
            operation.call(world, chunk, randomTickSpeed)
            return
        }
        val start = System.nanoTime()
        operation.call(world, chunk, randomTickSpeed)
        val elapsed = System.nanoTime() - start
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

    @WrapOperation(
        method = ["tickChunks"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/SpawnHelper;spawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/WorldChunk;Lnet/minecraft/world/SpawnHelper\$Info;ZZZ)V"
        )]
    )
    private fun measureMobSpawn(
        world: ServerWorld,
        chunk: WorldChunk,
        info: SpawnHelper.Info,
        spawnAnimals: Boolean,
        spawnMonsters: Boolean,
        rareSpawn: Boolean,
        operation: Operation<Void>
    ) {
        if (!ChunkPerfRuntime.enabled) {
            operation.call(world, chunk, info, spawnAnimals, spawnMonsters, rareSpawn)
            return
        }
        val start = System.nanoTime()
        operation.call(world, chunk, info, spawnAnimals, spawnMonsters, rareSpawn)
        val elapsed = System.nanoTime() - start
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
