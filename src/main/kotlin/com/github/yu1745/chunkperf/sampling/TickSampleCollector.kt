package com.github.yu1745.chunkperf.sampling

import com.github.yu1745.chunkperf.ChunkPerfRuntime
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import java.util.PriorityQueue

internal data class ChunkDimKey(val dimension: RegistryKey<World>, val chunkPos: Long)

internal data class RawChunkSnapshot(
    val key: ChunkDimKey,
    val randomTickNs: Long,
    val blockEntityNs: Long,
    val entityTickNs: Long,
    val mobSpawnNs: Long,
    val blockEntityTickCount: Long,
    val entityTickCount: Long,
    val intervalTicks: Int,
    val hotspots: List<BlockPosSample>
) {
    val totalNs: Long get() = saturatedSum(randomTickNs, blockEntityNs, entityTickNs, mobSpawnNs)
    val chunkX: Int get() = ChunkPos.getPackedX(key.chunkPos)
    val chunkZ: Int get() = ChunkPos.getPackedZ(key.chunkPos)
}

object TickSampleCollector {
    private val stats = HashMap<ChunkDimKey, ChunkTickStats>()
    private var globalBeHotspotEntryCount = 0
    var droppedNewChunkSamples: Long = 0L
        private set
    var droppedNewBeHotspots: Long = 0L
        private set

    fun record(
        dimension: RegistryKey<World>,
        chunkPos: Long,
        source: SampleSource,
        ns: Long,
        tick: Long
    ) {
        if (ns < 0L) return
        val key = ChunkDimKey(dimension, chunkPos)
        val chunkStats = stats[key] ?: run {
            if (stats.size >= ChunkPerfRuntime.config.maxTrackedChunks) {
                droppedNewChunkSamples = saturatedAdd(droppedNewChunkSamples, 1L)
                return
            }
            ChunkTickStats(tick).also { stats[key] = it }
        }
        chunkStats.add(source, ns, tick)
    }

    fun recordBlockEntity(
        dimension: RegistryKey<World>,
        pos: BlockPos,
        ns: Long,
        blockEntityName: String?,
        tick: Long
    ) {
        val chunkPos = ChunkPos.toLong(pos.x shr 4, pos.z shr 4)
        record(dimension, chunkPos, SampleSource.BLOCK_ENTITY, ns, tick)
        val config = ChunkPerfRuntime.config
        if (ns < config.beHotspotMinRecordNs || config.beHotspotTopN == 0 || config.maxPublishedBeHotspots == 0) return
        val key = ChunkDimKey(dimension, chunkPos)
        val chunkStats = stats[key] ?: return
        var hotspots = chunkStats.blockEntityHotspots
        val immutablePos = pos.toImmutable()
        val existing = hotspots?.get(immutablePos)
        if (existing != null) {
            existing.ns = saturatedAdd(existing.ns, ns)
            if (blockEntityName != null) existing.blockEntityName = blockEntityName
            return
        }
        if ((hotspots?.size ?: 0) >= config.maxBeHotspotEntriesPerChunk ||
            globalBeHotspotEntryCount >= config.maxGlobalBeHotspotEntries
        ) {
            droppedNewBeHotspots = saturatedAdd(droppedNewBeHotspots, 1L)
            return
        }
        if (hotspots == null) {
            hotspots = HashMap()
            chunkStats.blockEntityHotspots = hotspots
        }
        hotspots[immutablePos] = MutableBlockEntitySample(ns, blockEntityName)
        globalBeHotspotEntryCount++
    }

    internal fun drainTop(intervalTicks: Int, maxPublishedChunks: Int): List<RawChunkSnapshot> {
        val chunkHeap = PriorityQueue<RawChunkSnapshot>(compareBy { it.totalNs })
        for ((key, value) in stats) {
            val raw = RawChunkSnapshot(
                key, value.randomTickNs, value.blockEntityNs, value.entityTickNs,
                value.mobSpawnNs, value.blockEntityTickCount, value.entityTickCount,
                intervalTicks, emptyList()
            )
            value.randomTickNs = 0L
            value.blockEntityNs = 0L
            value.entityTickNs = 0L
            value.mobSpawnNs = 0L
            value.blockEntityTickCount = 0L
            value.entityTickCount = 0L
            if (raw.totalNs == 0L) continue
            if (chunkHeap.size < maxPublishedChunks) chunkHeap.add(raw)
            else if (raw.totalNs > chunkHeap.peek().totalNs) {
                chunkHeap.poll()
                chunkHeap.add(raw)
            }
        }

        val selected = chunkHeap.associateBy { it.key }.toMutableMap()
        val candidateComparator = compareBy<HotspotCandidate> { it.sample.ns }
        val globalHeap = PriorityQueue(candidateComparator)
        val config = ChunkPerfRuntime.config
        for ((key, value) in stats) {
            val hotspots = value.blockEntityHotspots
            value.blockEntityHotspots = null
            if (hotspots == null) continue
            globalBeHotspotEntryCount -= hotspots.size
            if (key !in selected || config.beHotspotTopN == 0) continue
            val localHeap = PriorityQueue<Map.Entry<BlockPos, MutableBlockEntitySample>>(compareBy { it.value.ns })
            for (entry in hotspots.entries) {
                if (localHeap.size < config.beHotspotTopN) localHeap.add(entry)
                else if (entry.value.ns > localHeap.peek().value.ns) {
                    localHeap.poll()
                    localHeap.add(entry)
                }
            }
            for (entry in localHeap) {
                val candidate = HotspotCandidate(key, entry.key, entry.value)
                if (globalHeap.size < config.maxPublishedBeHotspots) globalHeap.add(candidate)
                else if (candidate.sample.ns > globalHeap.peek().sample.ns) {
                    globalHeap.poll()
                    globalHeap.add(candidate)
                }
            }
        }
        check(globalBeHotspotEntryCount == 0) { "ChunkPerf hotspot count became inconsistent" }

        val grouped = globalHeap.groupBy { it.key }
        return selected.values
            .map { raw ->
                val published = grouped[raw.key]
                    ?.sortedByDescending { it.sample.ns }
                    ?.map { BlockPosSample(it.pos.toImmutable(), it.sample.ns, it.sample.blockEntityName) }
                    ?: emptyList()
                raw.copy(hotspots = java.util.List.copyOf(published))
            }
            .sortedByDescending { it.totalNs }
    }

    fun pruneStale(currentTick: Long, maxAgeTicks: Long) {
        val iterator = stats.entries.iterator()
        while (iterator.hasNext()) {
            val value = iterator.next().value
            if (currentTick - value.lastWriteTick <= maxAgeTicks) continue
            globalBeHotspotEntryCount -= value.blockEntityHotspots?.size ?: 0
            iterator.remove()
        }
        check(globalBeHotspotEntryCount >= 0)
    }

    fun clearBlockEntityHotspots() {
        for (value in stats.values) value.blockEntityHotspots = null
        globalBeHotspotEntryCount = 0
    }

    fun clear() {
        stats.clear()
        globalBeHotspotEntryCount = 0
        droppedNewChunkSamples = 0L
        droppedNewBeHotspots = 0L
    }

    private data class HotspotCandidate(
        val key: ChunkDimKey,
        val pos: BlockPos,
        val sample: MutableBlockEntitySample
    )
}
