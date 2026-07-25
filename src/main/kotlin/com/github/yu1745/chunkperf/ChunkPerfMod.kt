package com.github.yu1745.chunkperf

import com.github.yu1745.chunkperf.config.ChunkPerfConfig
import com.github.yu1745.chunkperf.integration.ClaimLookup
import com.github.yu1745.chunkperf.integration.ClaimProvider
import com.github.yu1745.chunkperf.integration.ClaimProviderNoop
import com.github.yu1745.chunkperf.integration.ClaimRegistry
import com.github.yu1745.chunkperf.sampling.ChunkSampleSnapshot
import com.github.yu1745.chunkperf.sampling.ClaimSnapshot
import com.github.yu1745.chunkperf.sampling.TickSampleCollector
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ChunkPerfRuntime {
    val logger: Logger = LoggerFactory.getLogger("ChunkPerf")
    lateinit var config: ChunkPerfConfig
    val latestSnapshot = AtomicReference<List<ChunkSampleSnapshot>>(emptyList())
    private val samplingEnabled = AtomicBoolean(false)
    private val blockEntityHotspotsEnabled = AtomicBoolean(false)
    var currentTick: Long = 0L
    var lastSnapshotTick: Long = 0L
    @Volatile var snapshotIntervalTicks: Int = 20

    val enabled: Boolean get() = samplingEnabled.get()
    val detailedBlockEntitySampling: Boolean get() = blockEntityHotspotsEnabled.get()

    fun setEnabled(enabled: Boolean) = samplingEnabled.set(enabled)

    fun setDetailedBlockEntitySampling(enabled: Boolean) = blockEntityHotspotsEnabled.set(enabled)

    inline fun safeRecord(block: () -> Unit) {
        if (!enabled) return
        try {
            block()
        } catch (e: Exception) {
            disableAfterFailure(e)
        } catch (e: LinkageError) {
            disableAfterFailure(e)
        }
    }

    fun disableAfterFailure(error: Throwable) {
        if (samplingEnabled.compareAndSet(true, false)) {
            logger.error("Sampling failed and has been disabled for this server run", error)
        }
    }
}

class ChunkPerfMod : ModInitializer {
    override fun onInitialize() {
        ChunkPerfRuntime.config = ChunkPerfConfig.load(ChunkPerfRuntime.logger)
        installClaimProvider()
        ChunkPerfServerUi.register()
        ServerLifecycleEvents.SERVER_STARTED.register(::onServerStarted)
        ServerLifecycleEvents.SERVER_STOPPED.register(::onServerStopped)
        ServerTickEvents.START_SERVER_TICK.register(::onStartServerTick)
        ServerTickEvents.END_SERVER_TICK.register(::onEndServerTick)
    }

    private fun installClaimProvider() {
        val loader = FabricLoader.getInstance()
        if (!loader.isModLoaded("ftbchunks")) {
            ClaimRegistry.install(ClaimProviderNoop)
            return
        }
        val provider = try {
            loader.getEntrypoints("chunkperf:claim_provider", ClaimProvider::class.java).firstOrNull()
                ?: ClaimProviderNoop
        } catch (e: Exception) {
            ChunkPerfRuntime.logger.error("Unable to instantiate FTB Chunks integration; using unavailable claims", e)
            ClaimProviderNoop
        } catch (e: LinkageError) {
            ChunkPerfRuntime.logger.error("Incompatible FTB Chunks integration; using unavailable claims", e)
            ClaimProviderNoop
        }
        ClaimRegistry.install(provider)
    }

    private fun onServerStarted(server: MinecraftServer) {
        TickSampleCollector.clear()
        ChunkPerfRuntime.latestSnapshot.set(emptyList())
        ChunkPerfRuntime.currentTick = 0L
        ChunkPerfRuntime.lastSnapshotTick = 0L
        ChunkPerfRuntime.snapshotIntervalTicks = ChunkPerfRuntime.config.snapshotIntervalTicks
        ChunkPerfRuntime.setDetailedBlockEntitySampling(ChunkPerfRuntime.config.blockEntityHotspotsEnabled)
        ChunkPerfRuntime.setEnabled(ChunkPerfRuntime.config.enabled)
        ChunkPerfRuntime.logger.info("ChunkPerf sampling {}", if (ChunkPerfRuntime.enabled) "started" else "disabled by config")
    }

    private fun onServerStopped(server: MinecraftServer) {
        ChunkPerfRuntime.setEnabled(false)
        ChunkPerfRuntime.setDetailedBlockEntitySampling(false)
        TickSampleCollector.clear()
        ChunkPerfRuntime.latestSnapshot.set(emptyList())
        ClaimRegistry.reset()
    }

    private fun onStartServerTick(server: MinecraftServer) {
        if (ChunkPerfRuntime.currentTick < Long.MAX_VALUE) ChunkPerfRuntime.currentTick++
    }

    private fun onEndServerTick(server: MinecraftServer) {
        if (!ChunkPerfRuntime.enabled) return
        val tick = ChunkPerfRuntime.currentTick
        val elapsedTicks = tick - ChunkPerfRuntime.lastSnapshotTick
        if (elapsedTicks >= ChunkPerfRuntime.snapshotIntervalTicks) {
            val raw = TickSampleCollector.drainTop(elapsedTicks.toInt(), ChunkPerfRuntime.config.maxPublishedChunks)
            val builder = ArrayList<ChunkSampleSnapshot>(raw.size)
            for (sample in raw) {
                val claim = when (val lookup = ClaimRegistry.safeGetClaim(sample.key.dimension, sample.chunkX, sample.chunkZ)) {
                    ClaimLookup.Unavailable -> ClaimSnapshot.Unavailable
                    ClaimLookup.Wilderness -> ClaimSnapshot.Wilderness
                    is ClaimLookup.Claimed -> ClaimSnapshot.Claimed(
                        lookup.info.teamId,
                        lookup.info.teamName,
                        lookup.info.forceLoadConfigured,
                        lookup.info.actuallyForceLoaded
                    )
                }
                builder += ChunkSampleSnapshot(
                    dimension = sample.key.dimension.value,
                    chunkX = sample.chunkX,
                    chunkZ = sample.chunkZ,
                    claim = claim,
                    randomTickNs = sample.randomTickNs,
                    blockEntityNs = sample.blockEntityNs,
                    entityTickNs = sample.entityTickNs,
                    mobSpawnNs = sample.mobSpawnNs,
                    blockEntityTickCount = sample.blockEntityTickCount,
                    entityTickCount = sample.entityTickCount,
                    intervalTicks = sample.intervalTicks,
                    blockEntityHotspots = sample.hotspots
                )
            }
            val snapshots = Collections.unmodifiableList(builder)
        ChunkPerfRuntime.latestSnapshot.set(snapshots)
           ChunkPerfRuntime.lastSnapshotTick = tick
           ChunkPerfServerUi.publish(server)
        }
        if (tick % 1_000L == 0L) {
            TickSampleCollector.pruneStale(tick, ChunkPerfRuntime.config.pruneStaleAfterTicks)
        }
    }

}
