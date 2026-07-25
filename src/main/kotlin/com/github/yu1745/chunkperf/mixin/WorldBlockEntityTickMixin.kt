package com.github.yu1745.chunkperf.mixin

import com.github.yu1745.chunkperf.ChunkPerfRuntime
import com.github.yu1745.chunkperf.sampling.SampleSource
import com.github.yu1745.chunkperf.sampling.TickSampleCollector
import com.llamalad7.mixinextras.sugar.Local
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.BlockEntityTickInvoker
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(World::class)
abstract class WorldBlockEntityTickMixin {
    @Unique
    private var cpBeStart = 0L

    @Inject(
        method = ["tickBlockEntities"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/BlockEntityTickInvoker;tick()V"
        )]
    )
    private fun cpBeforeBETick(ci: CallbackInfo) {
        if (ChunkPerfRuntime.enabled) cpBeStart = System.nanoTime()
    }

    @Inject(
        method = ["tickBlockEntities"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/BlockEntityTickInvoker;tick()V",
            shift = At.Shift.AFTER
        )]
    )
    private fun cpAfterBETick(ci: CallbackInfo, @Local invoker: BlockEntityTickInvoker) {
        if (!ChunkPerfRuntime.enabled || cpBeStart == 0L) return
        val elapsed = System.nanoTime() - cpBeStart
        cpBeStart = 0L
        val world = (this as Any) as? ServerWorld ?: return
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
