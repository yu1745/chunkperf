package com.github.yu1745.chunkperf.mixin

import com.github.yu1745.chunkperf.ChunkPerfRuntime
import com.github.yu1745.chunkperf.sampling.SampleSource
import com.github.yu1745.chunkperf.sampling.TickSampleCollector
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import net.minecraft.entity.Entity
import net.minecraft.server.world.ServerWorld
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import java.util.function.Consumer

@Mixin(ServerWorld::class)
abstract class EntityTickMixin {
    @WrapOperation(
        // Entity iteration is compiled into ServerWorld's synthetic lambda body.
        method = ["method_31420"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;tickEntity(Ljava/util/function/Consumer;Lnet/minecraft/entity/Entity;)V"
        )]
    )
    private fun measureEntityTick(
        world: ServerWorld,
        tickConsumer: Consumer<Entity>,
        entity: Entity,
        operation: Operation<Void>
    ) {
        if (!ChunkPerfRuntime.enabled) {
            operation.call(world, tickConsumer, entity)
            return
        }
        val chunkPos = entity.chunkPos.toLong()
        val start = System.nanoTime()
        operation.call(world, tickConsumer, entity)
        val elapsed = System.nanoTime() - start
        ChunkPerfRuntime.safeRecord {
            TickSampleCollector.record(
                world.registryKey,
                chunkPos,
                SampleSource.ENTITY_TICK,
                elapsed,
                ChunkPerfRuntime.currentTick
            )
        }
    }
}
