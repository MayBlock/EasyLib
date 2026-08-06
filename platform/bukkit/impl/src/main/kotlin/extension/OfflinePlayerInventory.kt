package com.github.mayblock.easylib.base.impl.bukkit.extension

import com.github.mayblock.easylib.base.impl.bukkit.util.stack
import de.tr7zw.nbtapi.NBT
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.HumanEntity
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import java.io.File
import kotlin.math.min

class OfflinePlayerInventory internal constructor(
    private val offlinePlayer: OfflinePlayer
) : PlayerInventory, InventoryHolder {

    init {
        if (offlinePlayer.isOnline) {
            throw IllegalStateException("you cannot access this player offline inventory when this player is online!")
        }
    }

    private companion object {
        const val STORAGE_SIZE = 36
        const val SLOT_OFFHAND = 40
        const val TOTAL_SIZE = 41
    }

    private val playerData = Bukkit.getWorlds()[0].let { world ->
        File(world.worldFolder, "players/data/${offlinePlayer.uniqueId}.dat").apply {
            if (!exists()) createNewFile()
            if (!isFile || !canRead()) throw IllegalArgumentException("Player Data File is invalid")
        }
    }.let(NBT::getFileHandle)

    private fun equipmentSlotName(index: Int) =
        when (index) {
            0 -> "feet"
            1 -> "legs"
            2 -> "chest"
            3 -> "head"
            4 -> "offhand"
            else -> throw ArrayIndexOutOfBoundsException("index is out of bounds: $index")
        }
    private fun setEquipment(index: Int, item: ItemStack?) =
        playerData.getOrCreateCompound("equipment").getOrCreateCompound(equipmentSlotName(index)).let { compound ->
            compound.clearNBT()
            if (item != null && !item.type.isAir) {
                compound.mergeCompound(NBT.itemStackToNBT(item))
            }
        }
    private fun getEquipment(index: Int) =
        playerData.getOrCreateCompound("equipment")
            .getCompound(equipmentSlotName(index))
            ?.let(NBT::itemStackFromNBT)
    private fun getPlayerInventory() = playerData.getCompoundList("Inventory")

    override fun getArmorContents(): Array<out ItemStack?> {
        return arrayOf(boots, leggings, chestplate, helmet)
    }

    override fun getExtraContents(): Array<out ItemStack?> {
        return arrayOf(getEquipment(4))
    }

    override fun getHelmet() = getEquipment(3)
    override fun getChestplate() = getEquipment(2)
    override fun getLeggings() = getEquipment(1)
    override fun getBoots() = getEquipment(0)

    override fun setItem(index: Int, item: ItemStack?) {
        when (index) {
            in 0 until STORAGE_SIZE -> {
                val inv = getPlayerInventory()
                inv.removeIf { it.getByte("Slot").toInt() == index }
                if (item != null && !item.type.isAir) {
                    NBT.itemStackToNBT(item).apply {
                        setByte("Slot", index.toByte())
                    }.let(inv::addCompound)
                }
            }
            in STORAGE_SIZE..SLOT_OFFHAND -> {
                setEquipment(index - STORAGE_SIZE, item)
            }
            else -> {
                throw ArrayIndexOutOfBoundsException("index is out of bounds: $index")
            }
        }
    }

    override fun setItem(slot: EquipmentSlot, item: ItemStack?) {
        when (slot) {
            EquipmentSlot.HAND -> setItemInMainHand(item)
            EquipmentSlot.OFF_HAND -> setItemInOffHand(item)
            EquipmentSlot.FEET -> boots = item
            EquipmentSlot.LEGS -> leggings = item
            EquipmentSlot.CHEST -> chestplate = item
            EquipmentSlot.HEAD -> helmet = item
            else -> throw IllegalArgumentException("Could not set slot ${slot.name} - not a valid slot for PlayerInventory")
        }
    }

    override fun getItem(slot: EquipmentSlot): ItemStack? {
        return when (slot) {
            EquipmentSlot.HAND -> itemInMainHand
            EquipmentSlot.OFF_HAND -> itemInOffHand
            EquipmentSlot.FEET -> boots
            EquipmentSlot.LEGS -> leggings
            EquipmentSlot.CHEST -> chestplate
            EquipmentSlot.HEAD -> helmet
            else -> throw IllegalArgumentException("Could not set slot ${slot.name} - not a valid slot for PlayerInventory")
        }
    }

    override fun getItem(index: Int): ItemStack? = when (index) {
        in 0 until STORAGE_SIZE -> {
            playerData.getCompoundList("Inventory")
                .firstOrNull { it.getByte("Slot").toInt() == index }
                ?.let(NBT::itemStackFromNBT)
        }
        in STORAGE_SIZE until SLOT_OFFHAND -> {
            armorContents.getOrNull(index - STORAGE_SIZE)
        }
        SLOT_OFFHAND -> {
            itemInOffHand.takeIf { !it.type.isAir }
        }
        else -> throw ArrayIndexOutOfBoundsException("index out of bounds: $index")
    }

    override fun setArmorContents(items: Array<out ItemStack?>) {
        if (items.isEmpty()) return
        require(items.size <= 4) { "this array is too large" }
        helmet = items.getOrNull(3)
        chestplate = items.getOrNull(2)
        leggings = items.getOrNull(1)
        boots = items.getOrNull(0)
    }

    override fun setExtraContents(items: Array<out ItemStack?>) {
        require(items.size <= 1) { "this array is too large" }
        setItemInOffHand(items[0])
    }

    override fun setHelmet(helmet: ItemStack?) {
        setEquipment(3, helmet)
    }

    override fun setChestplate(chestplate: ItemStack?) {
        setEquipment(2, chestplate)
    }

    override fun setLeggings(leggings: ItemStack?) {
        setEquipment(1, leggings)
    }

    override fun setBoots(boots: ItemStack?) {
        setEquipment(0, boots)
    }

    override fun getItemInMainHand(): ItemStack {
        val selectedSlot = getHeldItemSlot()
        return getPlayerInventory()
            .firstOrNull { it.getByte("Slot").toInt() == selectedSlot }
            ?.let(NBT::itemStackFromNBT)
            ?: stack(Material.AIR)
    }

    override fun setItemInMainHand(item: ItemStack?) {
        val selectedSlot = getHeldItemSlot()
        val invList = getPlayerInventory()
        val index = invList.indexOfFirst { it.getByte("Slot").toInt() == selectedSlot }
        if (item == null || item.type == Material.AIR) {
            invList.remove(index)
            return
        }
        if (index != -1) {
            setItem(selectedSlot, item)
        }
    }

    override fun getItemInOffHand(): ItemStack = getEquipment(4) ?: stack(Material.AIR)
    override fun setItemInOffHand(item: ItemStack?) {
        setEquipment(4, item)
    }

    @Deprecated("Deprecated in Java")
    override fun getItemInHand() = itemInMainHand

    @Deprecated("Deprecated in Java")
    override fun setItemInHand(stack: ItemStack?) {
        setItemInMainHand(stack)
    }

    override fun getHeldItemSlot(): Int = playerData.getInteger("SelectedItemSlot")

    override fun setHeldItemSlot(slot: Int) {
        playerData.setInteger("SelectedItemSlot", slot)
    }

    override fun getHolder(): HumanEntity? = null
    override fun getSize() = TOTAL_SIZE
    private var maxStackSize = 99
    override fun getMaxStackSize() = maxStackSize
    override fun setMaxStackSize(size: Int) {
        maxStackSize = size
    }

    fun getMaxItemStack(item: ItemStack) = min(maxStackSize, item.maxStackSize)

    private fun firstPartial(item: ItemStack): Int {
        if (item.type.isAir || item.amount <= 0) return -1
        return storageContents.indexOfFirst { existing ->
            existing != null && existing.amount < getMaxItemStack(item) && existing.isSimilar(item)
        }
    }

    override fun addItem(vararg items: ItemStack): HashMap<Int, ItemStack> {
        val leftover = HashMap<Int, ItemStack>()
        for ((index, originalItem) in items.withIndex()) {
            val item = originalItem.takeIf { !it.type.isAir } ?: continue
            val remaining = item.clone()
            while (true) {
                val partialSlot = firstPartial(remaining)
                if (partialSlot != -1) {
                    // 存在可堆叠的槽位
                    val partialItem = getItem(partialSlot)!!  // 必定非空
                    val maxStack = getMaxItemStack(partialItem)
                    val canAdd = maxStack - partialItem.amount
                    if (remaining.amount <= canAdd) {
                        // 完全装得下
                        partialItem.amount += remaining.amount
                        setItem(partialSlot, partialItem)   // 触发更新
                        break
                    } else {
                        // 只能部分装入
                        partialItem.amount = maxStack
                        setItem(partialSlot, partialItem)
                        remaining.amount -= canAdd
                    }
                } else {
                    // 没有可堆叠的物品，寻找空位
                    val emptySlot = firstEmpty()
                    if (emptySlot == -1) {
                        // 没有空间，剩余物品作为 leftover
                        leftover[index] = remaining
                        break
                    }
                    val maxStack = getMaxItemStack(remaining)
                    if (remaining.amount > maxStack) {
                        // 拆分堆叠
                        setItem(emptySlot, remaining.clone().apply { amount = maxStack })
                        remaining.amount -= maxStack
                        // 继续循环处理剩余部分
                    } else {
                        // 直接放入
                        setItem(emptySlot, remaining)
                        break
                    }
                }
            }
        }
        return leftover
    }

    override fun removeItem(vararg items: ItemStack): HashMap<Int, ItemStack> {
        val leftover = HashMap<Int, ItemStack>()
        for ((index, original) in items.withIndex()) {
            val item = original.takeIf { !it.type.isAir } ?: continue
            var toDelete = item.amount
            while (toDelete > 0) {
                val slot = first(item, false)  // 查找第一个匹配的物品槽位
                if (slot == -1) {
                    // 库存中已无该物品，剩余部分作为 leftover
                    leftover[index] = item.clone().apply { amount = toDelete }
                    break
                }
                val stackInSlot = getItem(slot)!!
                when {
                    stackInSlot.amount <= toDelete -> {
                        // 当前槽位全部消耗完
                        toDelete -= stackInSlot.amount
                        clear(slot)
                    }
                    else -> {
                        // 当前槽位只消耗部分
                        stackInSlot.amount -= toDelete
                        setItem(slot, stackInSlot)   // 触发更新
                        toDelete = 0
                    }
                }
            }
        }
        return leftover
    }

    override fun getContents(): Array<out ItemStack?> = Array(size) { getItem(it) }

    override fun setContents(items: Array<out ItemStack?>) {
        require(items.size <= size) { "Invalid inventory size (${items.size}); expected $size or less" }
        for (i in 0 until size) {
            setItem(i, items.getOrNull(i))
        }
    }

    override fun getStorageContents() = Array(STORAGE_SIZE) { getItem(it) }

    override fun setStorageContents(items: Array<out ItemStack?>) {
        require(items.size <= STORAGE_SIZE) { "Invalid inventory size (${items.size}); expected $size or less" }
        items.forEachIndexed { i, item ->
            setItem(i, item)
        }
    }

    override fun contains(material: Material): Boolean = storageContents.filterNotNull().any { it.type == material }
    override fun contains(item: ItemStack?): Boolean = item != null && storageContents.filterNotNull().any { it == item }
    override fun contains(material: Material, amount: Int): Boolean {
        if (amount <= 0) return true
        var remaining = amount
        for (item in storageContents) {
            if (item?.type == material) {
                remaining -= item.amount
                if (remaining <= 0) return true
            }
        }
        return false
    }

    override fun contains(item: ItemStack?, amount: Int): Boolean {
        if (item == null) return false
        if (amount <= 0) return true
        var remaining = amount
        for (i in storageContents) {
            if (item == i && --remaining <= 0) return true
        }
        return false
    }

    override fun containsAtLeast(item: ItemStack?, amount: Int): Boolean {
        if (item == null) return false
        if (amount <= 0) return true
        var remaining = amount
        for (itemStack in storageContents) {
            if (itemStack != null && item.isSimilar(itemStack)) {
                remaining -= itemStack.amount
                if (remaining <= 0) return true
            }
        }
        return false
    }

    override fun all(material: Material): HashMap<Int, out ItemStack> {
        val slots = hashMapOf<Int, ItemStack>()
        storageContents.forEachIndexed { i, itemStack ->
            if (material == itemStack?.type) {
                slots[i] = itemStack
            }
        }
        return slots
    }

    override fun all(item: ItemStack?): HashMap<Int, out ItemStack?> {
        val slots = hashMapOf<Int, ItemStack?>()
        storageContents.forEachIndexed { i, itemStack ->
            if (item == itemStack) {
                slots[i] = itemStack
            }
        }
        return slots
    }

    override fun first(material: Material): Int {
        val inventory = storageContents
        for (i in inventory.indices) {
            val item = inventory[i]
            if (item != null && item.type == material) {
                return i
            }
        }
        return -1
    }

    override fun first(item: ItemStack) = first(item, true)
    private fun first(item: ItemStack?, withAmount: Boolean): Int {
        if (item == null || item.type.isAir) {
            return -1
        }
        val inventory = storageContents
        return inventory.indexOfFirst { existing ->
            existing != null && if (withAmount) item == existing else item.isSimilar(existing)
        }
    }

    override fun firstEmpty() = storageContents.indexOfFirst { it == null }
    override fun isEmpty(): Boolean = contents.all { it == null }

    override fun remove(material: Material) {
        storageContents.forEachIndexed { i, item ->
            if (item != null && item.type == material) {
                clear(i)
            }
        }
    }

    override fun remove(item: ItemStack) {
        storageContents.forEachIndexed { i, itemStack ->
            if (itemStack != null && item == itemStack) {
                clear(i)
            }
        }
    }

    override fun clear(index: Int) {
        setItem(index, null)
    }

    override fun clear() {
        for (i in 0 until size) {
            setItem(i, null)
        }
    }

    override fun getViewers(): List<HumanEntity> = emptyList()
    override fun getType(): InventoryType = InventoryType.PLAYER

    override fun iterator(): MutableListIterator<ItemStack?> {
        TODO("Not yet implemented")
    }

    override fun iterator(index: Int): ListIterator<ItemStack?> {
        TODO("Not yet implemented")
    }

    override fun getLocation(): Location {
        val world = playerData.getString("Dimension").let { name ->
            when (name) {
                "minecraft:overworld" -> "world"
                "minecraft:the_nether" -> "world_the_nether"
                "minecraft:the_end" -> "world_the_end"
                else -> name
            }.let(Bukkit::getWorld)
        }
        val pos = playerData.getDoubleList("Pos")
        val rotation = playerData.getFloatList("Rotation")
        val x = pos.get(0)
        val y = pos.get(1)
        val z = pos.get(2)
        val yaw = rotation.get(0)
        val pitch = rotation.get(1)
        return Location(world, x, y, z, yaw, pitch)
    }

    override fun getInventory() = this
}