package dev.lrxh.blockChanger.util;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

public class GroupBuffer {
    public int[] indices;
    public BlockState[] states;
    public int size;

    public GroupBuffer(int initialCapacity) {
        if (initialCapacity <= 0) initialCapacity = 8;
        this.indices = new int[initialCapacity];
        this.states = new BlockState[initialCapacity];
        this.size = 0;
    }

    public synchronized void append(int idx, BlockState state) {
        if (size == indices.length) {
            final int newCap = indices.length + (indices.length >> 1);
            indices = Arrays.copyOf(indices, newCap);
            states = Arrays.copyOf(states, newCap);
        }
        indices[size] = idx;
        states[size] = state;
        size++;
    }
}
