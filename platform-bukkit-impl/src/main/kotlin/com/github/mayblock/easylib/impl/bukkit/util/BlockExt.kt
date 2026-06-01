package com.github.mayblock.easylib.impl.bukkit.util

import com.github.mayblock.easylib.api.util.Vector
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block

data class BlockArea(val pos1: Vector<Int>, val pos2: Vector<Int>) {

    val xRange = minOf(pos1.x, pos2.x)..maxOf(pos1.x, pos2.x)
    val yRange = minOf(pos1.y, pos2.y)..maxOf(pos1.y, pos2.y)
    val zRange = minOf(pos1.z, pos2.z)..maxOf(pos1.z, pos2.z)

    companion object {
        fun of(loc1: Location, loc2: Location): BlockArea {
            require(loc1.world == loc2.world) { "Locations must be in the same world" }
            return BlockArea(
                Vector(
                    loc1.blockX,
                    loc1.blockY,
                    loc1.blockZ
                ),
                Vector(
                    loc2.blockX,
                    loc2.blockY,
                    loc2.blockZ
                )
            )
        }
    }
}
fun BlockArea.contains(location: Location): Boolean =
    location.blockX in this.xRange && location.blockY in this.yRange && location.blockZ in this.zRange

fun BlockArea.contains(block: Block): Boolean =
    block.x in this.xRange && block.y in this.yRange && block.z in this.zRange

fun BlockArea.getBlocks(world: World): List<Block> {
    val blocks = mutableListOf<Block>()
    for (x in xRange) {
        for (y in yRange) {
            for (z in zRange) {
                blocks.add(world.getBlockAt(x, y, z))
            }
        }
    }
    return blocks
}