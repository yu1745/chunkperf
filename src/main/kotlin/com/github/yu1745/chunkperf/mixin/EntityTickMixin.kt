package com.github.yu1745.chunkperf.mixin

import com.github.yu1745.chunkperf.ChunkPerfRuntime
import com.github.yu1745.chunkperf.sampling.SampleSource
import com.github.yu1745.chunkperf.sampling.TickSampleCollector
import com.llamalad7.mixinextras.sugar.Local
import net.minecraft.entity.Entity
import net.minecraft.server.world.ServerWorld
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ServerWorld::class)
abstract class EntityTickMixin {
    @Unique
    private var cpEntityStart = 0L

    @Inject(
        method = ["method_31420"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;tickEntity(Ljava/util/function/Consumer;Lnet/minecraft/entity/Entity;)V"
        )]
    )
    private fun cpBeforeEntityTick(ci: CallbackInfo) {
        if (ChunkPerfRuntime.enabled) cpEntityStart = System.nanoTime()
    }

    @Inject(
        method = ["method_31420"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;tickEntity(Ljava/util/function/Consumer;Lnet/minecraft/entity/Entity;)V",
            shift = At.Shift.AFTER
        )]
    )
    private fun cpAfterEntityTick(ci: CallbackInfo, @Local entity: Entity) {
        if (!ChunkPerfRuntime.enabled || cpEntityStart == 0L) return
        val elapsed = System.nanoTime() - cpEntityStart
        cpEntityStart = 0L
        val world = this as Any as ServerWorld
        ChunkPerfRuntime.safeRecord {
            TickSampleCollector.record(
                world.registryKey,
                entity.chunkPos.toLong(),
                SampleSource.ENTITY_TICK,
                elapsed,
                ChunkPerfRuntime.currentTick
            )
        }
    }
}
