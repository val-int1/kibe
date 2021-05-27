package io.github.lucaargolo.kibe.blocks.chunkloader

import io.github.lucaargolo.kibe.MOD_CONFIG
import io.github.lucaargolo.kibe.blocks.getEntityType
import net.fabricmc.fabric.api.block.entity.BlockEntityClientSerializable
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.entity.BlockEntity
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.*

class ChunkLoaderBlockEntity(val block: Block, pos: BlockPos, state: BlockState): BlockEntity(getEntityType(block), pos, state), BlockEntityClientSerializable {

    var ownerUUID = ""

    enum class DisabledReason {
        NONE,
        OWNER_OFFLINE,
        OWNER_LONG_GONE,
        TOO_MANY_LOADERS
    }

    var disabledReason = DisabledReason.NONE
    private var ownerLastSeen = System.currentTimeMillis()
    private var delay = 0
    private var dirty = false

    var enabledChunks = mutableListOf(
        Pair(-1, -1), Pair(0, -1), Pair(1, -1),
        Pair(-1, 0), Pair(0, 0), Pair(1, 0),
        Pair(-1, 1), Pair(0, 1), Pair(1, 1)
    )

    override fun writeNbt(tag: NbtCompound): NbtCompound {
        tag.putString("ownerUUID", ownerUUID)
        tag.putLong("ownerLastSeen", ownerLastSeen)
        tag.putString("disabledReason", disabledReason.name)
        val list = NbtList()
        enabledChunks.forEach {
            val innerTag = NbtCompound()
            innerTag.putInt("x", it.first)
            innerTag.putInt("z", it.second)
            list.add(innerTag)
        }
        tag.put("enabledChunks", list)
        return super.writeNbt(tag)
    }

    override fun readNbt(tag: NbtCompound) {
        super.readNbt(tag)
        ownerUUID = tag.getString("ownerUUID")
        ownerLastSeen = tag.getLong("ownerLastSeen")
        disabledReason = try { DisabledReason.valueOf(tag.getString("disabledReason")) } catch (ignored: Exception) { ignored.printStackTrace(); DisabledReason.NONE }
        val list = tag.get("enabledChunks") as? NbtList
        list?.let {
            enabledChunks = mutableListOf()
            list.forEach {
                val ct = it as NbtCompound
                enabledChunks.add(Pair(ct.getInt("x"), ct.getInt("z")))
            }
        }
    }

    override fun toClientTag(tag: NbtCompound): NbtCompound {
        return writeNbt(tag)
    }

    override fun fromClientTag(tag: NbtCompound) {
        readNbt(tag)
    }

    override fun markDirty() {
        super.markDirty()
        dirty = true
    }

    fun markDirtyAndSync() {
        markDirty()
        (world as? ServerWorld)?.let { sync() }
    }

    companion object {
        fun tick(world: World, pos: BlockPos, state: BlockState, blockEntity: ChunkLoaderBlockEntity) {
            if(delay >= 20) {
                val playerUUID = try { UUID.fromString(ownerUUID) } catch (ignored: Exception) { null }
                if (!cachedState[Properties.ENABLED]) {
                    when (disabledReason) {
                        DisabledReason.NONE -> {
                            world?.setBlockState(pos, cachedState.with(Properties.ENABLED, true))
                            disabledReason = DisabledReason.NONE
                            markDirtyAndSync()
                        }
                        DisabledReason.OWNER_OFFLINE, DisabledReason.OWNER_LONG_GONE -> {
                            playerUUID?.let { validUUID ->
                                (world as? ServerWorld)?.server?.playerManager?.getPlayer(validUUID)?.let {
                                    world?.setBlockState(pos, cachedState.with(Properties.ENABLED, true))
                                    ownerLastSeen = System.currentTimeMillis()
                                    disabledReason = DisabledReason.NONE
                                    markDirtyAndSync()
                                }
                            }
                        }
                        DisabledReason.TOO_MANY_LOADERS -> {
                            playerUUID?.let { validUUID ->
                                (world as? ServerWorld)?.let { world ->
                                    val chunkLoaderState = world.server.overworld.persistentStateManager.getOrCreate({ ChunkLoaderState(world.server, "kibe_chunk_loaders") }, "kibe_chunk_loaders")
                                    if(chunkLoaderState.getLoaded(validUUID) < MOD_CONFIG.chunkLoaderModule.maxPerPlayer || MOD_CONFIG.chunkLoaderModule.maxPerPlayer < 0) {
                                        world.setBlockState(pos, cachedState.with(Properties.ENABLED, true))
                                        disabledReason = DisabledReason.NONE
                                        markDirtyAndSync()
                                    }
                                }
                            }
                        }
                    }
                }
                if (cachedState[Properties.ENABLED]) {
                    if(disabledReason != DisabledReason.NONE) {
                        world?.setBlockState(pos, cachedState.with(Properties.ENABLED, false))
                        return
                    }

                    (world as? ServerWorld)?.let { world ->
                        val chunkLoaderState = world.server.overworld.persistentStateManager.getOrCreate({ ChunkLoaderState(world.server, "kibe_chunk_loaders") }, "kibe_chunk_loaders")

                        playerUUID?.let { validUUID ->
                            val player = world.server.playerManager.getPlayer(validUUID)
                            if(player != null) {
                                ownerLastSeen = System.currentTimeMillis()
                            }else if (MOD_CONFIG.chunkLoaderModule.checkForPlayer) {
                                world.setBlockState(pos, cachedState.with(Properties.ENABLED, false))
                                disabledReason = DisabledReason.OWNER_OFFLINE
                                markDirtyAndSync()
                                return
                            }
                            if (MOD_CONFIG.chunkLoaderModule.maxPerPlayer > -1 && chunkLoaderState.getLoaded(validUUID) > MOD_CONFIG.chunkLoaderModule.maxPerPlayer) {
                                world.setBlockState(pos, cachedState.with(Properties.ENABLED, false))
                                disabledReason = DisabledReason.TOO_MANY_LOADERS
                                markDirtyAndSync()
                                return
                            }
                        }

                        if (MOD_CONFIG.chunkLoaderModule.maxOfflineTime > -1 && ((System.currentTimeMillis() - ownerLastSeen)/1000) > MOD_CONFIG.chunkLoaderModule.maxOfflineTime) {
                            world.setBlockState(pos, cachedState.with(Properties.ENABLED, false))
                            disabledReason = DisabledReason.OWNER_LONG_GONE
                            markDirtyAndSync()
                            return
                        }

                        if(dirty) {
                            chunkLoaderState.removePos(pos, world)
                            dirty = false
                        }

                        chunkLoaderState.addPos(pos, world)
                    }

                }
                delay = 0
            }
            delay++
        }
    }


}