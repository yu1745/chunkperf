package com.github.yu1745.chunkperf.client

import com.github.yu1745.chunkperf.ChunkPerfNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class BlockEntityPerfScreen : Screen(Text.literal("Block Entity Performance")) {
    private var scrollOffset = 0

    override fun init() {
        super.init()
        ClientPlayNetworking.send(ChunkPerfNetworking.BE_SUBSCRIBE, PacketByteBufs.empty())
        ClientPlayNetworking.send(ChunkPerfNetworking.MOB_SUBSCRIBE, PacketByteBufs.empty())
    }

    override fun close() {
        ClientPlayNetworking.send(ChunkPerfNetworking.BE_UNSUBSCRIBE, PacketByteBufs.empty())
        ClientPlayNetworking.send(ChunkPerfNetworking.MOB_UNSUBSCRIBE, PacketByteBufs.empty())
        super.close()
    }

    override fun shouldPause(): Boolean = false

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xFF101318.toInt())
        context.drawCenteredTextWithShadow(textRenderer, "BE / 生物性能", width / 2, 8, 0xFFFFFF)
        val dimension = client?.world?.registryKey?.value
        val samples = if (dimension == null) emptyList() else ChunkPerfClientState.samples.get()
            .asSequence()
            .filter { it.dimension == dimension }
            .filter { !ChunkPerfClientState.showOnlyOwnedChunks || it.ownedByViewer }
            .flatMap { chunk ->
                val ticks = chunk.intervalTicks.coerceAtLeast(1)
                chunk.blockEntityHotspots.asSequence().map { hotspot -> TimedBe(hotspot, hotspot.ns / 1_000_000.0 / ticks) }
            }
            .toList()
        val rows = samples
            .groupBy { it.sample.blockId }
            .map { (blockId, entries) ->
                BeRow(blockId, entries.first().sample.name, entries.size, entries.sumOf { it.msPerTick })
            }
            .sortedByDescending { it.msPerTick }
        val mobSamples = if (dimension == null) emptyList() else ChunkPerfClientState.samples.get()
            .asSequence().filter { it.dimension == dimension }
            .filter { !ChunkPerfClientState.showOnlyOwnedChunks || it.ownedByViewer }
            .flatMap { chunk ->
                val ticks = chunk.intervalTicks.coerceAtLeast(1)
                chunk.mobHotspots.asSequence().map { ClientMobRow(it.name, it.entityId, it.ns / 1_000_000.0 / ticks) }
            }.toList()
        val mobRows = mobSamples.groupBy { it.entityId }
            .map { (id, entries) -> MobRow(id, MobNameTranslations.translate(id, entries.first().name), entries.size, entries.sumOf { it.msPerTick }) }
            .sortedByDescending { it.msPerTick }

        context.drawTextWithShadow(textRenderer, "当前维度: ${dimension ?: "不可用"}", 10, 24, 0xAEB8C2)
        renderViewModeButtons(context, mouseX, mouseY)
        val columnGap = 8
        val columnWidth = (width - 16 - columnGap) / 2
        context.drawTextWithShadow(textRenderer, "BE / 方块", 10, 39, 0xDDE7EE)
        context.drawTextWithShadow(textRenderer, "实例", columnWidth / 2 - 2, 39, 0xDDE7EE)
        context.drawTextWithShadow(textRenderer, "耗时", columnWidth - 68, 39, 0xDDE7EE)
        context.drawTextWithShadow(textRenderer, "生物", 10 + columnWidth + columnGap, 39, 0xDDE7EE)
        context.drawTextWithShadow(textRenderer, "实例", 10 + columnWidth + columnGap + columnWidth / 2 - 2, 39, 0xDDE7EE)
        context.drawTextWithShadow(textRenderer, "耗时", 10 + columnWidth + columnGap + columnWidth - 68, 39, 0xDDE7EE)
        context.fill(8, 51, width - 8, 52, 0xFF59636E.toInt())

        val rowHeight = 22
        val visibleRows = max(1, (height - 64) / rowHeight)
        val totalGridRows = max(rows.size, mobRows.size)
        scrollOffset = scrollOffset.coerceIn(0, max(0, totalGridRows - visibleRows))
        val endGridRow = min(totalGridRows, scrollOffset + visibleRows)
        var y = 56
        var hoveredRow: BeRow? = null
        for (gridRow in scrollOffset until endGridRow) {
            for (column in 0..1) {
                val index = gridRow
                val row = if (column == 0) rows.getOrNull(index) else null
                val mobRow = if (column == 1) mobRows.getOrNull(index) else null
                if (row == null && mobRow == null) continue
                val x = 8 + column * (columnWidth + columnGap)
                if ((gridRow - scrollOffset) % 2 == 1) context.fill(x, y - 2, x + columnWidth, y + 19, 0x182F3944)
                if (row != null) {
                    val block = Registries.BLOCK.get(row.blockId)
                    val stack = ItemStack(block.asItem())
                    if (!stack.isEmpty) context.drawItem(stack, x + 3, y)
                    context.drawTextWithShadow(textRenderer, row.count.toString(), x + columnWidth / 2, y + 4, 0xC8D2DC)
                } else if (mobRow != null) {
                    context.drawTextWithShadow(textRenderer, mobRow.name.take(18), x + 3, y + 4, 0xDDE7EE)
                    context.drawTextWithShadow(textRenderer, mobRow.count.toString(), x + columnWidth / 2, y + 4, 0xC8D2DC)
                }
                val cost = row?.msPerTick ?: mobRow!!.msPerTick
            context.drawTextWithShadow(
                textRenderer,
                formatMetric(cost),
                    x + columnWidth - 68,
                y + 4,
                if (cost >= 1.0) 0xFF6B6B else if (cost >= 0.1) 0xFFD166 else 0x9FE870
            )
                if (row != null && mouseX in x until (x + columnWidth) && mouseY in (y - 2) until (y + 19)) hoveredRow = row
            }
            y += rowHeight
        }

        if (rows.isEmpty() && mobRows.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, "正在采集；首个热点快照将在刷新周期结束后出现", width / 2, height / 2, 0xAEB8C2)
        } else {
            val first = scrollOffset * 2 + 1
            context.drawTextWithShadow(textRenderer, "BE ${rows.size} 类 · 生物 ${mobRows.size} 类 · ${samples.size} 个 BE 热点 · ${mobSamples.size} 个生物热点 · 滚轮滚动", 10, height - 12, 0xAEB8C2)
        }
        super.render(context, mouseX, mouseY, delta)
        if (hoveredRow != null) {
            val block = Registries.BLOCK.get(hoveredRow.blockId)
            val tooltip = ArrayList<Text>(3)
            tooltip += block.name
            tooltip += Text.literal(hoveredRow.blockId.toString())
            if (hoveredRow.beName != null && hoveredRow.beName != hoveredRow.blockId.toString()) {
                tooltip += Text.literal("BE: ${hoveredRow.beName}")
            }
            context.drawTooltip(textRenderer, tooltip, mouseX, mouseY)
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, amount: Double): Boolean {
        scrollOffset = max(0, scrollOffset - amount.toInt())
        return true
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && mouseY in 20.0..32.0) {
            val startX = width - 202
            when {
                mouseX >= startX && mouseX < startX + 38 -> ChunkPerfClientState.showOnlyOwnedChunks = false
                mouseX >= startX + 40 && mouseX < startX + 78 -> ChunkPerfClientState.showOnlyOwnedChunks = true
                mouseX >= startX + 84 && mouseX < startX + 122 -> ChunkPerfClientState.showPercentage = false
                mouseX >= startX + 124 && mouseX < startX + 178 -> ChunkPerfClientState.showPercentage = true
                else -> return super.mouseClicked(mouseX, mouseY, button)
            }
            scrollOffset = 0
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun renderViewModeButtons(context: DrawContext, mouseX: Int, mouseY: Int) {
        val startX = width - 202
        val y = 20
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

    private fun formatMetric(msPerTick: Double): String = if (ChunkPerfClientState.showPercentage) {
        String.format(Locale.ROOT, "%.2f%%", msPerTick / 50.0 * 100.0)
    } else {
        String.format(Locale.ROOT, "%.4f ms/tick", msPerTick)
    }

    private data class TimedBe(val sample: ClientBlockEntitySample, val msPerTick: Double)
    private data class BeRow(
        val blockId: net.minecraft.util.Identifier,
        val beName: String?,
        val count: Int,
        val msPerTick: Double
    )
    private data class ClientMobRow(val name: String, val entityId: net.minecraft.util.Identifier, val msPerTick: Double)
    private data class MobRow(val entityId: net.minecraft.util.Identifier, val name: String, val count: Int, val msPerTick: Double)
}
