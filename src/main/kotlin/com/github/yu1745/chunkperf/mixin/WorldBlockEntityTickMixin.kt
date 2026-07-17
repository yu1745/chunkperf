package com.github.yu1745.chunkperf.mixin

import com.github.yu1745.chunkperf.ChunkPerfRuntime
import com.github.yu1745.chunkperf.sampling.SampleSource
import com.github.yu1745.chunkperf.sampling.TickSampleCollector
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.World
import net.minecraft.world.chunk.BlockEntityTickInvoker
import net.minecraft.util.math.ChunkPos
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(World::class)
abstract class WorldBlockEntityTickMixin {
    @WrapOperation(
        method = ["tickBlockEntities"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/BlockEntityTickInvoker;tick()V"
        )]
    )
    private fun measureBlockEntityTick(
        invoker: BlockEntityTickInvoker,
        operation: Operation<Void>
    ) {
        val world = (this as Any) as? ServerWorld
        if (!ChunkPerfRuntime.enabled || world == null) {
            operation.call(invoker)
            return
        }
        val start = System.nanoTime()
        operation.call(invoker)
        val elapsed = System.nanoTime() - start
        ChunkPerfRuntime.safeRecord {
            // Lithium's sleeping block entity ticker deliberately exposes a null position.
            // Such a ticker cannot be attributed to a chunk, so skip only this sample.
            val pos = invoker.pos ?: return@safeRecord
            if (ChunkPerfRuntime.detailedBlockEntitySampling) {
                val config = ChunkPerfRuntime.config
                val name = if (elapsed >= config.beHotspotMinRecordNs && config.beHotspotTopN > 0) invoker.name else null
                TickSampleCollector.recordBlockEntity(
                    world.registryKey, pos, elapsed, name, ChunkPerfRuntime.currentTick
                )
            } else {
                TickSampleCollector.record(
                    world.registryKey,
                    ChunkPos.toLong(pos.x shr 4, pos.z shr 4),
                    SampleSource.BLOCK_ENTITY,
                    elapsed,
                    ChunkPerfRuntime.currentTick
                )
            }
        }
    }
}
