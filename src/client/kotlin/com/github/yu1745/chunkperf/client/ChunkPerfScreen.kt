package com.github.yu1745.chunkperf.client

import com.github.yu1745.chunkperf.ChunkPerfNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.minecraft.block.MapColor
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.world.Heightmap
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ChunkPerfScreen : Screen(Text.literal("ChunkPerf")) {
    private var centerX = Double.NaN
    private var centerZ = Double.NaN
    private var zoomLevel = 1
    private var dragging = false
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var surfaceSlice = 0
    private var cachedDimension: net.minecraft.util.Identifier? = null
    private var summaryCache: VisibleSummary? = null
    private var summarySamples: List<ClientChunkSample>? = null
    private var summaryCenterX = Double.NaN
    private var summaryCenterZ = Double.NaN
    private var summaryScale = Double.NaN
    private var clusterCacheSamples: List<ClientChunkSample>? = null
    private var clusterCacheDimension: net.minecraft.util.Identifier? = null
    private var clusterCacheRadius = -1
    private var clusterCache: List<TopCluster> = emptyList()
    private val surfaceColors = object : LinkedHashMap<Long, Int>(65_536, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>?): Boolean = size > 250_000
    }

    private val blocksPerPixel: Double get() = 2.0.pow(zoomLevel)
    private val contentTop get() = 28
    private val contentBottom get() = height - 120
    private val leftX1 get() = 8
    private val leftX2 get() = width / 2 - 3
    private val rightX1 get() = width / 2 + 3
    private val rightX2 get() = width - 8

    override fun init() {
        super.init()
        val player = client?.player
        if (centerX.isNaN() && player != null) {
            centerX = player.x
            centerZ = player.z
        }
        ClientPlayNetworking.send(ChunkPerfNetworking.SUBSCRIBE, PacketByteBufs.empty())
    }

    override fun close() {
        ClientPlayNetworking.send(ChunkPerfNetworking.UNSUBSCRIBE, PacketByteBufs.empty())
        super.close()
    }

    override fun shouldPause(): Boolean = false

    override fun tick() {
        updateSurfaceSlice()
        val target = ChunkPerfClientState.pendingTarget ?: return
        val world = client?.world ?: return
        if (world.registryKey.value != target.dimension || !world.isChunkLoaded(target.chunkX, target.chunkZ)) return
        centerX = target.chunkX * 16.0 + 8.0
        centerZ = target.chunkZ * 16.0 + 8.0
        ChunkPerfClientState.pendingTarget = null
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xFF101318.toInt())
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF)
        context.drawTextWithShadow(textRenderer, "地表地图（ClientWorld）", leftX1, 17, 0xDDE7EE)
        context.drawTextWithShadow(textRenderer, "区块性能热力图", rightX1, 17, 0xDDE7EE)
        renderViewModeButtons(context, mouseX, mouseY)
        renderIntervalButtons(context, mouseX, mouseY)
        renderSurfaceMap(context)
        renderHeatMap(context)
        renderDividerAndStatus(context)
        renderTopLinks(context, mouseX, mouseY)
        renderTooltip(context, mouseX, mouseY)
    }

    private fun renderSurfaceMap(context: DrawContext) {
        context.fill(leftX1, contentTop, leftX2, contentBottom, 0xFF252A30.toInt())
        val step = SURFACE_SAMPLE_PIXEL_STEP
        var sy = contentTop
        while (sy < contentBottom) {
            var sx = leftX1
            while (sx < leftX2) {
                val wx = surfaceSampleCoordinate(screenToWorldX(sx, leftX1, leftX2))
                val wz = surfaceSampleCoordinate(screenToWorldZ(sy))
                val color = surfaceColors[packBlockXZ(wx, wz)] ?: 0xFF343940.toInt()
                context.fill(sx, sy, min(sx + step, leftX2), min(sy + step, contentBottom), color)
                sx += step
            }
            sy += step
        }
        drawCrosshair(context, (leftX1 + leftX2) / 2, (contentTop + contentBottom) / 2)
    }

    private fun renderHeatMap(context: DrawContext) {
        context.fill(rightX1, contentTop, rightX2, contentBottom, 0xFF20252A.toInt())
        val dimension = client?.world?.registryKey?.value ?: return
        val samples = scopedSamples().filter { it.dimension == dimension }
        val maxMs = samples.maxOfOrNull { it.msPerTick }?.coerceAtLeast(0.001) ?: 0.001
        for (sample in samples) {
            val x1 = worldToScreenX(sample.chunkX * 16.0, rightX1, rightX2)
            val x2 = worldToScreenX(sample.chunkX * 16.0 + 16.0, rightX1, rightX2)
            val y1 = worldToScreenY(sample.chunkZ * 16.0)
            val y2 = worldToScreenY(sample.chunkZ * 16.0 + 16.0)
            if (x2 < rightX1 || x1 > rightX2 || y2 < contentTop || y1 > contentBottom) continue
            val intensity = sqrt((sample.msPerTick / maxMs).coerceIn(0.0, 1.0))
            val red = (255 * intensity).roundToInt()
            val green = (220 * (1.0 - intensity)).roundToInt()
            val color = 0xD0000000.toInt() or (red shl 16) or (green shl 8) or 32
            context.fill(max(x1, rightX1), max(y1, contentTop), min(x2, rightX2), min(y2, contentBottom), color)
            context.drawBorder(max(x1, rightX1), max(y1, contentTop), max(1, min(x2, rightX2) - max(x1, rightX1)), max(1, min(y2, contentBottom) - max(y1, contentTop)), 0x55333333)
        }
        drawCrosshair(context, (rightX1 + rightX2) / 2, (contentTop + contentBottom) / 2)
    }

    private fun renderDividerAndStatus(context: DrawContext) {
        context.fill(width / 2 - 1, contentTop, width / 2 + 1, contentBottom, 0xFF59636E.toInt())
        val target = ChunkPerfClientState.pendingTarget
        val status = if (target == null) {
            String.format(Locale.ROOT, "中心 %.1f, %.1f  |  1 px ≈ %.2f blocks  |  滚轮缩放 · 左键拖拽 · 右键TP", centerX, centerZ, blocksPerPixel)
        } else {
            "正在传送并等待 ClientWorld 加载 ${target.dimension} [${target.chunkX}, ${target.chunkZ}]…"
        }
        context.drawCenteredTextWithShadow(textRenderer, status, width / 2, contentBottom + 5, if (target == null) 0xC8D2DC else 0xFFD166)
        val summary = visibleSummary()
        context.drawCenteredTextWithShadow(
            textRenderer,
            "可视范围 ${formatMetric(summary.totalMsPerTick)}  |  随机 ${formatMetric(summary.randomMsPerTick)} · BE ${formatMetric(summary.blockEntityMsPerTick)}",
            width / 2, contentBottom + 16, 0x9FE870
        )
        context.drawCenteredTextWithShadow(
            textRenderer,
            "实体 ${formatMetric(summary.entityMsPerTick)} · 生物生成 ${formatMetric(summary.mobSpawnMsPerTick)}  |  " +
                "BE数 ${formatCount(summary.blockEntityCountPerTick)} · 实体数 ${formatCount(summary.entityCountPerTick)}  |  " +
                "${summary.sampledChunks}/${summary.visibleChunks} chunks",
            width / 2, contentBottom + 27, 0x9FE870
        )
    }

    private fun renderIntervalButtons(context: DrawContext, mouseX: Int, mouseY: Int) {
        val choices = intArrayOf(20, 40, 100, 200)
        val buttonW = 28
        val startX = rightX2 - choices.size * (buttonW + 2)
        context.drawTextWithShadow(textRenderer, "刷新", startX - 30, 17, 0xAEB8C2)
        for (i in choices.indices) {
            val x = startX + i * (buttonW + 2)
            val selected = ChunkPerfClientState.selectedIntervalTicks == choices[i]
            val hovered = mouseX in x until (x + buttonW) && mouseY in 14 until 26
            context.fill(x, 14, x + buttonW, 26, if (selected) 0xFF3C7A57.toInt() else if (hovered) 0xFF4A5562.toInt() else 0xFF303943.toInt())
            context.drawCenteredTextWithShadow(textRenderer, "${choices[i] / 20}s", x + buttonW / 2, 16, 0xFFFFFF)
        }
    }

    private fun renderViewModeButtons(context: DrawContext, mouseX: Int, mouseY: Int) {
        val y = 14
        val startX = max(leftX1 + 100, leftX2 - 196)
        drawModeButton(context, startX, y, 38, "当前", !ChunkPerfClientState.showOnlyOwnedChunks, mouseX, mouseY)
        drawModeButton(context, startX + 40, y, 38, "我的", ChunkPerfClientState.showOnlyOwnedChunks, mouseX, mouseY)
        drawModeButton(context, startX + 84, y, 38, "时间", !ChunkPerfClientState.showPercentage, mouseX, mouseY)
        drawModeButton(context, startX + 124, y, 54, "百分比", ChunkPerfClientState.showPercentage, mouseX, mouseY)
    }

    private fun drawModeButton(context: DrawContext, x: Int, y: Int, w: Int, label: String, selected: Boolean, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in x until (x + w) && mouseY in y until (y + 12)
        context.fill(x, y, x + w, y + 12, if (selected) 0xFF3C7A57.toInt() else if (hovered) 0xFF4A5562.toInt() else 0xFF303943.toInt())
        context.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + 2, 0xFFFFFF)
    }

    private fun renderTopLinks(context: DrawContext, mouseX: Int, mouseY: Int) {
        val dimension = client?.world?.registryKey?.value ?: return
        val columns = 3
        val gap = 4
        val buttonW = (width - 16 - gap * (columns - 1)) / columns
        val maxCount = 6
        val top = clusteredTop(dimension, maxCount)
        val count = top.size
        val firstRowY = contentBottom + 53
        context.drawTextWithShadow(textRenderer, "Top N 快捷定位", 8, contentBottom + 40, 0xFFFFFF)
        renderClusterRadiusButtons(context, mouseX, mouseY)
        for (i in 0 until count) {
            val x = 8 + (i % columns) * (buttonW + gap)
            val y = firstRowY + (i / columns) * 24
            val hovered = mouseX in x until (x + buttonW) && mouseY in y until (y + 20)
            context.fill(x, y, x + buttonW, y + 20, if (hovered) 0xFF48596B.toInt() else 0xFF2F3944.toInt())
            val cluster = top[i]
            val sample = cluster.representative
            val label = "[${sample.chunkX},${sample.chunkZ}] ${formatMetric(cluster.msPerTick)}${if (cluster.memberCount > 1) " ×${cluster.memberCount}" else ""}"
            context.drawCenteredTextWithShadow(textRenderer, label, x + buttonW / 2, y + 6, if (hovered) 0xFFFFFF else 0xD6E2EC)
        }
    }

    private fun renderClusterRadiusButtons(context: DrawContext, mouseX: Int, mouseY: Int) {
        val choices = intArrayOf(0, 1, 2, 4, 8)
        val buttonW = 24
        val startX = 104
        val y = contentBottom + 37
        context.drawTextWithShadow(textRenderer, "聚类半径", startX, y + 2, 0xAEB8C2)
        for (i in choices.indices) {
            val x = startX + 52 + i * (buttonW + 2)
            val selected = ChunkPerfClientState.selectedClusterRadiusChunks == choices[i]
            val hovered = mouseX in x until (x + buttonW) && mouseY in y until (y + 12)
            context.fill(x, y, x + buttonW, y + 12, if (selected) 0xFF3C7A57.toInt() else if (hovered) 0xFF4A5562.toInt() else 0xFF303943.toInt())
            context.drawCenteredTextWithShadow(textRenderer, choices[i].toString(), x + buttonW / 2, y + 2, 0xFFFFFF)
        }
    }

    private fun renderTooltip(context: DrawContext, mouseX: Int, mouseY: Int) {
        if (mouseX !in rightX1 until rightX2 || mouseY !in contentTop until contentBottom) return
        val dimension = client?.world?.registryKey?.value ?: return
        val wx = screenToWorldX(mouseX, rightX1, rightX2)
        val wz = screenToWorldZ(mouseY)
        val cx = floor(wx / 16.0).toInt()
        val cz = floor(wz / 16.0).toInt()
        val sample = scopedSamples().firstOrNull { it.dimension == dimension && it.chunkX == cx && it.chunkZ == cz } ?: return
        val ticks = sample.intervalTicks.coerceAtLeast(1)
        val lines = listOf(
            Text.literal("Chunk [$cx, $cz]"),
            Text.literal("平均总计 ${formatMetric(sample.msPerTick)}"),
            Text.literal("随机 ${formatMetric(sample.randomTickNs / 1e6 / ticks)}  BE ${formatMetric(sample.blockEntityNs / 1e6 / ticks)}"),
            Text.literal("实体 ${formatMetric(sample.entityTickNs / 1e6 / ticks)}  生物生成 ${formatMetric(sample.mobSpawnNs / 1e6 / ticks)}")
        )
        context.drawTooltip(textRenderer, lines, mouseX, mouseY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && mouseY in 14.0..26.0) {
            val modeStartX = max(leftX1 + 100, leftX2 - 196)
            when {
                mouseX >= modeStartX && mouseX < modeStartX + 38 -> ChunkPerfClientState.showOnlyOwnedChunks = false
                mouseX >= modeStartX + 40 && mouseX < modeStartX + 78 -> ChunkPerfClientState.showOnlyOwnedChunks = true
                mouseX >= modeStartX + 84 && mouseX < modeStartX + 122 -> ChunkPerfClientState.showPercentage = false
                mouseX >= modeStartX + 124 && mouseX < modeStartX + 178 -> ChunkPerfClientState.showPercentage = true
                else -> Unit
            }
            if (mouseX >= modeStartX && mouseX < modeStartX + 178) return true
            val choices = intArrayOf(20, 40, 100, 200)
            val buttonW = 28
            val startX = rightX2 - choices.size * (buttonW + 2)
            for (i in choices.indices) {
                val x = startX + i * (buttonW + 2)
                if (mouseX >= x && mouseX < x + buttonW) {
                    setSnapshotInterval(choices[i])
                    return true
                }
            }
        }
        if (button == 1 && mouseY in contentTop.toDouble()..contentBottom.toDouble()) {
            val dimension = client?.world?.registryKey?.value ?: return true
            val worldX = when {
                mouseX in leftX1.toDouble()..leftX2.toDouble() ->
                    screenToWorldX(mouseX.toInt(), leftX1, leftX2)
                mouseX in rightX1.toDouble()..rightX2.toDouble() ->
                    screenToWorldX(mouseX.toInt(), rightX1, rightX2)
                else -> return true
            }
            val worldZ = screenToWorldZ(mouseY.toInt())
            val chunkX = floor(worldX / 16.0).toInt()
            val chunkZ = floor(worldZ / 16.0).toInt()
            locate(dimension, chunkX, chunkZ)
            return true
        }
        if (button == 0) {
            val dimension = client?.world?.registryKey?.value
            val radiusChoices = intArrayOf(0, 1, 2, 4, 8)
            val radiusStartX = 104 + 52
            val radiusY = contentBottom + 37
            if (mouseY >= radiusY && mouseY < radiusY + 12) {
                for (i in radiusChoices.indices) {
                    val x = radiusStartX + i * 26
                    if (mouseX >= x && mouseX < x + 24) {
                        ChunkPerfClientState.selectedClusterRadiusChunks = radiusChoices[i]
                        return true
                    }
                }
            }
            if (dimension != null) {
                val columns = 3
                val gap = 4
                val buttonW = (width - 16 - gap * (columns - 1)) / columns
                val firstRowY = contentBottom + 53
                val row = ((mouseY - firstRowY) / 24).toInt()
                val column = ((mouseX - 8) / (buttonW + gap)).toInt()
                if (row in 0..1 && column in 0 until columns && mouseY >= firstRowY + row * 24 && mouseY < firstRowY + row * 24 + 20) {
                    val index = row * columns + column
                    val top = clusteredTop(dimension, 6)
                    val x = 8 + column * (buttonW + gap)
                    if (mouseX >= x && mouseX < x + buttonW && index < top.size) locate(top[index].representative)
                    return true
                }
            }
            if (mouseY in contentTop.toDouble()..contentBottom.toDouble() && mouseX in leftX1.toDouble()..rightX2.toDouble()) {
                dragging = true
                lastMouseX = mouseX
                lastMouseY = mouseY
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (dragging && button == 0) {
            centerX -= (mouseX - lastMouseX) * blocksPerPixel
            centerZ -= (mouseY - lastMouseY) * blocksPerPixel
            lastMouseX = mouseX
            lastMouseY = mouseY
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) dragging = false
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, amount: Double): Boolean {
        val oldScale = blocksPerPixel
        val anchorX = if (mouseX < width / 2) screenToWorldX(mouseX.toInt(), leftX1, leftX2) else screenToWorldX(mouseX.toInt(), rightX1, rightX2)
        val anchorZ = screenToWorldZ(mouseY.toInt())
        zoomLevel = (zoomLevel - if (amount > 0) 1 else -1).coerceIn(-2, 6)
        if (oldScale != blocksPerPixel) {
            val newAnchorX = if (mouseX < width / 2) screenToWorldX(mouseX.toInt(), leftX1, leftX2) else screenToWorldX(mouseX.toInt(), rightX1, rightX2)
            val newAnchorZ = screenToWorldZ(mouseY.toInt())
            centerX += anchorX - newAnchorX
            centerZ += anchorZ - newAnchorZ
        }
        return true
    }

    private fun locate(sample: ClientChunkSample) {
        locate(sample.dimension, sample.chunkX, sample.chunkZ)
    }

    private fun clusteredTop(dimension: net.minecraft.util.Identifier, limit: Int): List<TopCluster> {
        val allSamples = scopedSamples()
        val radius = ChunkPerfClientState.selectedClusterRadiusChunks
        if (clusterCacheSamples === allSamples && clusterCacheDimension == dimension && clusterCacheRadius == radius) {
            return clusterCache.take(limit)
        }
        val samples = allSamples.filter { it.dimension == dimension }.sortedByDescending { it.msPerTick }
        if (samples.isEmpty() || limit <= 0) return emptyList()
        val byPosition = HashMap<Long, Int>(samples.size * 2)
        for (index in samples.indices) byPosition[packChunkXZ(samples[index].chunkX, samples[index].chunkZ)] = index
        val used = BooleanArray(samples.size)
        val result = ArrayList<TopCluster>(samples.size)
        for (seedIndex in samples.indices) {
            if (used[seedIndex]) continue
            val seed = samples[seedIndex]
            var total = 0.0
            var members = 0
            for (dx in -radius..radius) for (dz in -radius..radius) {
                val candidateIndex = byPosition[packChunkXZ(seed.chunkX + dx, seed.chunkZ + dz)] ?: continue
                if (!used[candidateIndex]) {
                    val candidate = samples[candidateIndex]
                    used[candidateIndex] = true
                    total += candidate.msPerTick
                    members++
                }
            }
            result += TopCluster(seed, total, members)
        }
        clusterCacheSamples = allSamples
        clusterCacheDimension = dimension
        clusterCacheRadius = radius
        clusterCache = result.sortedByDescending { it.msPerTick }
        return clusterCache.take(limit)
    }

    private fun packChunkXZ(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xFFFFFFFFL)

    private fun scopedSamples(): List<ClientChunkSample> {
        val samples = ChunkPerfClientState.samples.get()
        return if (ChunkPerfClientState.showOnlyOwnedChunks) samples.filter { it.ownedByViewer } else samples
    }

    private fun formatMetric(msPerTick: Double): String = if (ChunkPerfClientState.showPercentage) {
        String.format(Locale.ROOT, "%.2f%%", msPerTick / 50.0 * 100.0)
    } else {
        String.format(Locale.ROOT, "%.3f ms/tick", msPerTick)
    }

    private fun locate(dimension: net.minecraft.util.Identifier, chunkX: Int, chunkZ: Int) {
        ChunkPerfClientState.pendingTarget = PendingTarget(dimension, chunkX, chunkZ)
        val buf = PacketByteBufs.create()
        buf.writeIdentifier(dimension)
        buf.writeInt(chunkX)
        buf.writeInt(chunkZ)
        ClientPlayNetworking.send(ChunkPerfNetworking.TELEPORT, buf)
    }

    private fun setSnapshotInterval(ticks: Int) {
        ChunkPerfClientState.selectedIntervalTicks = ticks
        val buf = PacketByteBufs.create()
        buf.writeVarInt(ticks)
        ClientPlayNetworking.send(ChunkPerfNetworking.SET_INTERVAL, buf)
    }

    private fun visibleSummary(): VisibleSummary {
        val samples = scopedSamples()
        if (summarySamples === samples && summaryCenterX == centerX && summaryCenterZ == centerZ && summaryScale == blocksPerPixel) {
            return summaryCache ?: VisibleSummary.EMPTY
        }
        val dimension = client?.world?.registryKey?.value ?: return VisibleSummary.EMPTY
        val halfWidthBlocks = (rightX2 - rightX1) * blocksPerPixel / 2.0
        val halfHeightBlocks = (contentBottom - contentTop) * blocksPerPixel / 2.0
        val minChunkX = floor((centerX - halfWidthBlocks) / 16.0).toInt()
        val maxChunkX = floor((centerX + halfWidthBlocks) / 16.0).toInt()
        val minChunkZ = floor((centerZ - halfHeightBlocks) / 16.0).toInt()
        val maxChunkZ = floor((centerZ + halfHeightBlocks) / 16.0).toInt()
        var random = 0.0
        var blockEntity = 0.0
        var entity = 0.0
        var spawn = 0.0
        var blockEntityCount = 0.0
        var entityCount = 0.0
        var count = 0
        for (sample in samples) {
            if (sample.dimension != dimension || sample.chunkX !in minChunkX..maxChunkX || sample.chunkZ !in minChunkZ..maxChunkZ) continue
            val ticks = sample.intervalTicks.coerceAtLeast(1)
            random += sample.randomTickNs / 1_000_000.0 / ticks
            blockEntity += sample.blockEntityNs / 1_000_000.0 / ticks
            entity += sample.entityTickNs / 1_000_000.0 / ticks
            spawn += sample.mobSpawnNs / 1_000_000.0 / ticks
            blockEntityCount += sample.blockEntityTickCount.toDouble() / ticks
            entityCount += sample.entityTickCount.toDouble() / ticks
            count++
        }
        val result = VisibleSummary(
            random, blockEntity, entity, spawn, blockEntityCount, entityCount, count,
            ((maxChunkX - minChunkX + 1).toLong() * (maxChunkZ - minChunkZ + 1).toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
        summarySamples = samples
        summaryCenterX = centerX
        summaryCenterZ = centerZ
        summaryScale = blocksPerPixel
        summaryCache = result
        return result
    }

    /** Refreshes one twentieth of the visible surface samples per client tick. */
    private fun updateSurfaceSlice() {
        val world = client?.world ?: return
        val dimension = world.registryKey.value
        if (cachedDimension != dimension) {
            surfaceColors.clear()
            cachedDimension = dimension
            surfaceSlice = 0
        }
        val step = SURFACE_SAMPLE_PIXEL_STEP
        val columns = ((leftX2 - leftX1) + step - 1) / step
        var row = 0
        var sy = contentTop
        while (sy < contentBottom) {
            var column = 0
            var sx = leftX1
            while (sx < leftX2) {
                val index = row * columns + column
                if (index % 20 == surfaceSlice) {
                    val wx = surfaceSampleCoordinate(screenToWorldX(sx, leftX1, leftX2))
                    val wz = surfaceSampleCoordinate(screenToWorldZ(sy))
                    val key = packBlockXZ(wx, wz)
                    if (world.isChunkLoaded(wx shr 4, wz shr 4)) {
                        val y = world.getTopY(Heightmap.Type.WORLD_SURFACE, wx, wz) - 1
                        val pos = BlockPos(wx, y, wz)
                        surfaceColors[key] = 0xFF000000.toInt() or world.getBlockState(pos).getMapColor(world, pos).color
                    }
                }
                column++
                sx += step
            }
            row++
            sy += step
        }
        surfaceSlice = (surfaceSlice + 1) % 20
    }

    private fun packBlockXZ(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xFFFFFFFFL)

    /** Anchors surface samples to a world-space grid so viewport movement can reuse cached values. */
    private fun surfaceSampleCoordinate(worldCoordinate: Double): Int {
        val stride = max(1, (SURFACE_SAMPLE_PIXEL_STEP * blocksPerPixel).roundToInt())
        return floor(worldCoordinate / stride).toInt() * stride
    }

    private fun screenToWorldX(screenX: Int, x1: Int, x2: Int): Double = centerX + (screenX - (x1 + x2) / 2.0) * blocksPerPixel
    private fun screenToWorldZ(screenY: Int): Double = centerZ + (screenY - (contentTop + contentBottom) / 2.0) * blocksPerPixel
    private fun worldToScreenX(worldX: Double, x1: Int, x2: Int): Int = ((x1 + x2) / 2.0 + (worldX - centerX) / blocksPerPixel).roundToInt()
    private fun worldToScreenY(worldZ: Double): Int = ((contentTop + contentBottom) / 2.0 + (worldZ - centerZ) / blocksPerPixel).roundToInt()

    private fun drawCrosshair(context: DrawContext, x: Int, y: Int) {
        context.fill(x - 4, y, x + 5, y + 1, 0xFFFFFFFF.toInt())
        context.fill(x, y - 4, x + 1, y + 5, 0xFFFFFFFF.toInt())
    }

    private data class VisibleSummary(
        val randomMsPerTick: Double,
        val blockEntityMsPerTick: Double,
        val entityMsPerTick: Double,
        val mobSpawnMsPerTick: Double,
        val blockEntityCountPerTick: Double,
        val entityCountPerTick: Double,
        val sampledChunks: Int,
        val visibleChunks: Int
    ) {
        val totalMsPerTick: Double get() = randomMsPerTick + blockEntityMsPerTick + entityMsPerTick + mobSpawnMsPerTick

        companion object {
            val EMPTY = VisibleSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0)
        }
    }

    private fun formatCount(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private data class TopCluster(val representative: ClientChunkSample, val msPerTick: Double, val memberCount: Int)

    companion object {
        private const val SURFACE_SAMPLE_PIXEL_STEP = 2
    }
}
