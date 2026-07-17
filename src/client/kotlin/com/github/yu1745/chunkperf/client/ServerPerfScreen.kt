package com.github.yu1745.chunkperf.client

import com.github.yu1745.chunkperf.ChunkPerfNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.resource.language.I18n
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.Locale
import java.util.UUID

class ServerPerfScreen : Screen(Text.literal("ChunkPerf Server")) {
    private val radiusChoices = intArrayOf(0, 1, 2, 4, 8)
    private var cachedSamples: List<ClientChunkSample>? = null
    private var cachedRadius = -1
    private var cachedClusters: List<ServerCluster> = emptyList()

    override fun init() {
        super.init()
        ClientPlayNetworking.send(ChunkPerfNetworking.SUBSCRIBE, PacketByteBufs.empty())
    }

    override fun close() {
        ClientPlayNetworking.send(ChunkPerfNetworking.UNSUBSCRIBE, PacketByteBufs.empty())
        super.close()
    }

    override fun shouldPause(): Boolean = false

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xFF101318.toInt())
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF)
        renderRadiusButtons(context, mouseX, mouseY)

        val clusters = clusteredTop(TOP_N)
        context.drawTextWithShadow(textRenderer, "全维度 Top ${clusters.size}/$TOP_N", 8, 34, 0xDDE7EE)
        context.drawTextWithShadow(textRenderer, "维度", 36, 34, 0x8F9AA5)
        context.drawTextWithShadow(textRenderer, "归属", width / 3, 34, 0x8F9AA5)
        context.drawTextWithShadow(textRenderer, "区块", width / 2 + 30, 34, 0x8F9AA5)
        context.drawTextWithShadow(textRenderer, "占用", width - 118, 34, 0x8F9AA5)

        clusters.forEachIndexed { index, cluster ->
            val y = LIST_TOP + index * ROW_HEIGHT
            val hovered = mouseX in 8 until width - 8 && mouseY in y until y + ROW_HEIGHT - 2
            context.fill(8, y, width - 8, y + ROW_HEIGHT - 2, if (hovered) 0xFF48596B.toInt() else if (index % 2 == 0) 0xFF252C34.toInt() else 0xFF20262D.toInt())
            context.drawTextWithShadow(textRenderer, "${index + 1}.", 14, y + 5, 0xAEB8C2)
            context.drawTextWithShadow(textRenderer, dimensionName(cluster.representative.dimension), 36, y + 5, 0xD6E2EC)
            context.drawTextWithShadow(textRenderer, cluster.ownerLabel, width / 3, y + 5, if (cluster.teams.size > 1) 0xFFD166 else 0xD6E2EC)
            context.drawTextWithShadow(textRenderer, "[${cluster.representative.chunkX}, ${cluster.representative.chunkZ}]", width / 2 + 30, y + 5, 0xFFFFFF)
            val suffix = if (cluster.memberCount > 1) "  ×${cluster.memberCount}" else ""
            context.drawTextWithShadow(textRenderer, formatMetric(cluster.msPerTick) + suffix, width - 118, y + 5, 0x9FE870)
        }

        val total = clusters.sumOf { it.msPerTick }
        context.drawTextWithShadow(textRenderer, "显示项合计 ${formatMetric(total)} · 点击条目传送", 8, height - 14, 0xAEB8C2)
    }

    private fun renderRadiusButtons(context: DrawContext, mouseX: Int, mouseY: Int) {
        context.drawTextWithShadow(textRenderer, "聚类半径", 8, 20, 0xAEB8C2)
        radiusChoices.forEachIndexed { index, radius ->
            val x = 64 + index * 28
            val selected = ChunkPerfClientState.selectedServerClusterRadiusChunks == radius
            val hovered = mouseX in x until x + 26 && mouseY in 18 until 30
            context.fill(x, 18, x + 26, 30, if (selected) 0xFF3C7A57.toInt() else if (hovered) 0xFF4A5562.toInt() else 0xFF303943.toInt())
            context.drawCenteredTextWithShadow(textRenderer, radius.toString(), x + 13, 20, 0xFFFFFF)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)
        if (mouseY in 18.0..30.0) {
            radiusChoices.forEachIndexed { index, radius ->
                val x = 64 + index * 28
                if (mouseX >= x && mouseX < x + 26) {
                    ChunkPerfClientState.selectedServerClusterRadiusChunks = radius
                    return true
                }
            }
        }
        val index = ((mouseY - LIST_TOP) / ROW_HEIGHT).toInt()
        val clusters = clusteredTop(TOP_N)
        if (index in clusters.indices && mouseX >= 8 && mouseX < width - 8 &&
            mouseY >= LIST_TOP + index * ROW_HEIGHT && mouseY < LIST_TOP + (index + 1) * ROW_HEIGHT - 2
        ) {
            val cluster = clusters[index]
            if (cluster.teams.size > 1) {
                client?.setScreen(TeamListScreen(this, cluster))
            } else {
                locate(cluster.representative)
            }
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun clusteredTop(limit: Int): List<ServerCluster> {
        val raw = ChunkPerfClientState.samples.get()
        val samples = if (ChunkPerfClientState.showOnlyOwnedChunks) raw.filter { it.ownedByViewer } else raw
        val radius = ChunkPerfClientState.selectedServerClusterRadiusChunks
        if (cachedSamples === raw && cachedRadius == radius) return cachedClusters.take(limit)

        val result = ArrayList<ServerCluster>()
        for ((_, dimensionSamples) in samples.groupBy { it.dimension }) {
            val sorted = dimensionSamples.sortedByDescending { it.msPerTick }
            val positions = HashMap<Long, Int>(sorted.size * 2)
            sorted.indices.forEach { positions[pack(sorted[it].chunkX, sorted[it].chunkZ)] = it }
            val used = BooleanArray(sorted.size)
            for (seedIndex in sorted.indices) {
                if (used[seedIndex]) continue
                val seed = sorted[seedIndex]
                var total = 0.0
                var members = 0
                val teams = LinkedHashMap<UUID, String>()
                for (dx in -radius..radius) for (dz in -radius..radius) {
                    val candidateIndex = positions[pack(seed.chunkX + dx, seed.chunkZ + dz)] ?: continue
                    if (!used[candidateIndex]) {
                        used[candidateIndex] = true
                        total += sorted[candidateIndex].msPerTick
                        val candidate = sorted[candidateIndex]
                        if (candidate.ownerTeamId != null) {
                            teams[candidate.ownerTeamId] = candidate.ownerTeamName ?: candidate.ownerTeamId.toString()
                        }
                        members++
                    }
                }
                result += ServerCluster(seed, total, members, teams)
            }
        }
        cachedSamples = raw
        cachedRadius = radius
        cachedClusters = result.sortedByDescending { it.msPerTick }
        return cachedClusters.take(limit)
    }

    private fun dimensionName(id: Identifier): Text {
        if (id.namespace == "minecraft") {
            return when (id.path) {
                "overworld" -> Text.literal("主世界")
                "the_nether" -> Text.literal("下界")
                "the_end" -> Text.literal("末地")
                else -> null
            } ?: Text.literal(id.path)
        }
        val key = "dimension.${id.namespace}.${id.path.replace('/', '.')}"
        return if (I18n.hasTranslation(key)) Text.translatable(key) else Text.literal(id.toString())
    }

    private fun formatMetric(msPerTick: Double): String = if (ChunkPerfClientState.showPercentage) {
        String.format(Locale.ROOT, "%.2f%%", msPerTick / 50.0 * 100.0)
    } else {
        String.format(Locale.ROOT, "%.3f ms/tick", msPerTick)
    }

    private fun locate(sample: ClientChunkSample) {
        ChunkPerfClientState.pendingTarget = PendingTarget(sample.dimension, sample.chunkX, sample.chunkZ)
        val buf = PacketByteBufs.create()
        buf.writeIdentifier(sample.dimension)
        buf.writeInt(sample.chunkX)
        buf.writeInt(sample.chunkZ)
        ClientPlayNetworking.send(ChunkPerfNetworking.TELEPORT, buf)
        close()
    }

    private fun pack(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xFFFFFFFFL)

    private data class ServerCluster(
        val representative: ClientChunkSample,
        val msPerTick: Double,
        val memberCount: Int,
        val teams: Map<UUID, String>
    ) {
        val ownerLabel: String get() = when (teams.size) {
            0 -> "无主"
            1 -> teams.values.first()
            else -> "混合"
        }
    }

    private class TeamListScreen(
        private val parent: ServerPerfScreen,
        private val cluster: ServerCluster
    ) : Screen(Text.literal("聚类归属详情")) {
        override fun shouldPause(): Boolean = false

        override fun close() {
            client?.setScreen(parent)
        }

        override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
            context.fill(0, 0, width, height, 0xFF101318.toInt())
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF)
            val sample = cluster.representative
            context.drawCenteredTextWithShadow(
                textRenderer,
                "${parent.dimensionName(sample.dimension).string} [${sample.chunkX}, ${sample.chunkZ}] · ${cluster.memberCount} chunks",
                width / 2, 32, 0xAEB8C2
            )
            context.drawTextWithShadow(textRenderer, "包含团队 (${cluster.teams.size})", width / 2 - 100, 54, 0xFFD166)
            cluster.teams.values.sorted().forEachIndexed { index, name ->
                context.drawTextWithShadow(textRenderer, "• $name", width / 2 - 90, 70 + index * 14, 0xDDE7EE)
            }
            val buttonY = height - 34
            drawButton(context, width / 2 - 104, buttonY, 96, "返回", mouseX, mouseY)
            drawButton(context, width / 2 + 8, buttonY, 96, "传送到热点", mouseX, mouseY)
        }

        override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
            if (button == 0 && mouseY >= height - 34 && mouseY < height - 14) {
                if (mouseX >= width / 2 - 104 && mouseX < width / 2 - 8) {
                    close()
                    return true
                }
                if (mouseX >= width / 2 + 8 && mouseX < width / 2 + 104) {
                    parent.locate(cluster.representative)
                    return true
                }
            }
            return super.mouseClicked(mouseX, mouseY, button)
        }

        private fun drawButton(context: DrawContext, x: Int, y: Int, w: Int, label: String, mouseX: Int, mouseY: Int) {
            val hovered = mouseX in x until x + w && mouseY in y until y + 20
            context.fill(x, y, x + w, y + 20, if (hovered) 0xFF48596B.toInt() else 0xFF303943.toInt())
            context.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + 6, 0xFFFFFF)
        }
    }

    companion object {
        private const val TOP_N = 10
        private const val LIST_TOP = 48
        private const val ROW_HEIGHT = 18
    }
}
