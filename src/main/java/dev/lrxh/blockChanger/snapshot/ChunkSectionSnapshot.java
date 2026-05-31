package dev.lrxh.blockChanger.snapshot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

public record ChunkSectionSnapshot(CompoundTag nbt, ChunkPos position) {
}
