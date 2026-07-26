package com.github.yu1745.chunkperf.client

import net.minecraft.util.Identifier

/** Display names for the mob IDs that appear in the performance screen. */
object MobNameTranslations {
    private val names = mapOf(
        "minecraft:allay" to "悦灵", "minecraft:axolotl" to "美西螈", "minecraft:bat" to "蝙蝠",
        "minecraft:bee" to "蜜蜂", "minecraft:blaze" to "烈焰人", "minecraft:camel" to "骆驼",
        "minecraft:cat" to "猫", "minecraft:cave_spider" to "洞穴蜘蛛", "minecraft:chicken" to "鸡",
        "minecraft:cod" to "鳕鱼", "minecraft:cow" to "牛", "minecraft:creeper" to "苦力怕",
        "minecraft:dolphin" to "海豚", "minecraft:donkey" to "驴", "minecraft:drowned" to "溺尸",
        "minecraft:elder_guardian" to "远古守卫者", "minecraft:ender_dragon" to "末影龙",
        "minecraft:enderman" to "末影人", "minecraft:endermite" to "末影螨", "minecraft:evoker" to "唤魔者",
        "minecraft:fox" to "狐狸", "minecraft:frog" to "青蛙", "minecraft:ghast" to "恶魂",
        "minecraft:giant" to "巨人", "minecraft:glow_squid" to "发光鱿鱼", "minecraft:goat" to "山羊",
        "minecraft:guardian" to "守卫者", "minecraft:hoglin" to "疣猪兽", "minecraft:horse" to "马",
        "minecraft:husk" to "尸壳", "minecraft:illusioner" to "幻术师", "minecraft:iron_golem" to "铁傀儡",
        "minecraft:llama" to "羊驼", "minecraft:magma_cube" to "岩浆怪", "minecraft:mooshroom" to "哞菇",
        "minecraft:mule" to "骡", "minecraft:ocelot" to "豹猫", "minecraft:panda" to "熊猫",
        "minecraft:parrot" to "鹦鹉", "minecraft:phantom" to "幻翼", "minecraft:pig" to "猪",
        "minecraft:piglin" to "猪灵", "minecraft:piglin_brute" to "猪灵蛮兵", "minecraft:pillager" to "掠夺者",
        "minecraft:polar_bear" to "北极熊", "minecraft:pufferfish" to "河豚", "minecraft:rabbit" to "兔子",
        "minecraft:ravager" to "劫兽", "minecraft:salmon" to "鲑鱼", "minecraft:sheep" to "羊",
        "minecraft:shulker" to "潜影贝", "minecraft:silverfish" to "蠹虫", "minecraft:skeleton" to "骷髅",
        "minecraft:skeleton_horse" to "骷髅马", "minecraft:slime" to "史莱姆", "minecraft:sniffer" to "嗅探兽",
        "minecraft:snow_golem" to "雪傀儡", "minecraft:spider" to "蜘蛛", "minecraft:squid" to "鱿鱼",
        "minecraft:stray" to "流浪者", "minecraft:strider" to "炽足兽", "minecraft:tadpole" to "蝌蚪",
        "minecraft:trader_llama" to "行商羊驼", "minecraft:tropical_fish" to "热带鱼", "minecraft:turtle" to "海龟",
        "minecraft:vex" to "恼鬼", "minecraft:villager" to "村民", "minecraft:vindicator" to "卫道士",
        "minecraft:wandering_trader" to "流浪商人", "minecraft:warden" to "监守者", "minecraft:witch" to "女巫",
        "minecraft:wither" to "凋灵", "minecraft:wither_skeleton" to "凋灵骷髅", "minecraft:wolf" to "狼",
        "minecraft:zoglin" to "僵尸疣猪兽", "minecraft:zombie" to "僵尸", "minecraft:zombie_horse" to "僵尸马",
        "minecraft:zombie_villager" to "僵尸村民", "minecraft:zombified_piglin" to "僵尸猪灵",
        "touhou_little_maid:maid" to "女仆", "tconstruct:terracube" to "黏土史莱姆"
    )

    fun translate(id: Identifier, fallback: String): String = names[id.toString()] ?: fallback
}
