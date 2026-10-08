/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.fastasyncworldedit.core.extent;

import com.fastasyncworldedit.core.util.FoliaSupport;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;

import java.util.Set;

/**
 * Wraps a third-party extent (e.g. a block-logging extent such as CoreProtect) that was inserted into the edit pipeline via
 * {@link com.sk89q.worldedit.event.extent.EditSessionEvent} and the {@code allowed-plugins} list.
 *
 * <p>FAWE processes edits asynchronously on {@code FaweForkJoinThread}s, and its own block access goes through NMS, which is
 * safe under Folia's regionised threading. Third-party extents, however, frequently read the world through the regular Bukkit
 * API (e.g. {@code World#getBlockAt(..).getState()} to inspect the block being changed, or the block directly below it for
 * gravity/attached-block logging). On Folia those reads must happen on the region thread that owns the chunk, otherwise
 * {@code TickThread.ensureTickThread} throws {@code IllegalStateException: Cannot read world asynchronously}.</p>
 *
 * <p>This extent therefore redirects the bulk editing operations that flow through the third-party extent to the region thread
 * owning each affected chunk, splitting region operations per-chunk so that every dispatched sub-operation is confined to a
 * single region. {@link TaskManager#syncAt(java.util.function.Supplier, World, int, int)} runs the supplier inline when the
 * caller already owns the target region, so the only cost in the common case is the per-chunk split. On non-Folia platforms
 * this wrapper is never inserted (see {@code EditSessionBuilder#wrapExtent}).</p>
 *
 * @since TODO
 */
public class FoliaThirdPartyExtent extends PassthroughExtent {

    private final World world;

    /**
     * Create a new instance.
     *
     * @param extent the third-party extent to guard
     * @param world  the world the edit is operating on, used to resolve owning region threads
     */
    public FoliaThirdPartyExtent(Extent extent, World world) {
        super(extent);
        this.world = world;
    }

    private boolean needsRedirect() {
        // Only redirect when we are off the owning region thread (i.e. on a FAWE async thread). When FAWE happens to run the
        // edit on a tick thread already, the delegate call is safe and we avoid a pointless round-trip.
        return FoliaSupport.isFolia() && !FoliaSupport.isTickThread();
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block) throws WorldEditException {
        if (!needsRedirect()) {
            return getExtent().setBlock(position, block);
        }
        return FoliaSupport.getRethrowing(() -> TaskManager.taskManager().syncAt(
                () -> FoliaSupport.getRethrowing(() -> getExtent().setBlock(position, block)),
                world,
                position.x() >> 4,
                position.z() >> 4
        ));
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) throws WorldEditException {
        return setBlock(BlockVector3.at(x, y, z), block);
    }

    @Override
    public <B extends BlockStateHolder<B>> int setBlocks(Region region, B block) throws MaxChangedBlocksException {
        if (!needsRedirect()) {
            return getExtent().setBlocks(region, block);
        }
        return perChunk(region, sub -> getExtent().setBlocks(sub, block));
    }

    @Override
    public int setBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
        if (!needsRedirect()) {
            return getExtent().setBlocks(region, pattern);
        }
        return perChunk(region, sub -> getExtent().setBlocks(sub, pattern));
    }

    @Override
    public <B extends BlockStateHolder<B>> int replaceBlocks(Region region, Set<BaseBlock> filter, B replacement) throws
            MaxChangedBlocksException {
        if (!needsRedirect()) {
            return getExtent().replaceBlocks(region, filter, replacement);
        }
        return perChunk(region, sub -> getExtent().replaceBlocks(sub, filter, replacement));
    }

    @Override
    public int replaceBlocks(Region region, Set<BaseBlock> filter, Pattern pattern) throws MaxChangedBlocksException {
        if (!needsRedirect()) {
            return getExtent().replaceBlocks(region, filter, pattern);
        }
        return perChunk(region, sub -> getExtent().replaceBlocks(sub, filter, pattern));
    }

    @Override
    public int replaceBlocks(Region region, Mask mask, Pattern pattern) throws MaxChangedBlocksException {
        if (!needsRedirect()) {
            return getExtent().replaceBlocks(region, mask, pattern);
        }
        return perChunk(region, sub -> getExtent().replaceBlocks(sub, mask, pattern));
    }

    @FunctionalInterface
    private interface ChunkOperation {
        int apply(Region sub) throws MaxChangedBlocksException;
    }

    /**
     * Splits {@code region} into one sub-region per chunk and runs {@code op} for each on the region thread that owns that
     * chunk. The per-chunk sub-region is the exact intersection of the original region with the chunk's column, so arbitrary
     * region shapes (cuboid, cylinder, polygon, convex, ...) are preserved.
     */
    private int perChunk(Region region, ChunkOperation op) throws MaxChangedBlocksException {
        int affected = 0;
        for (BlockVector2 chunk : region.getChunks()) {
            final Region sub = intersectChunk(region, chunk.x(), chunk.z());
            if (sub == null) {
                continue;
            }
            affected += FoliaSupport.getRethrowing(() -> TaskManager.taskManager().syncAt(
                    () -> FoliaSupport.getRethrowing(() -> op.apply(sub)),
                    world,
                    chunk.x(),
                    chunk.z()
            ));
        }
        return affected;
    }

    /**
     * Builds a region representing {@code region} intersected with the given chunk column, or {@code null} if the chunk is a
     * simple fully-contained cuboid of the original and the original is itself a {@link CuboidRegion} (fast path).
     */
    private Region intersectChunk(Region region, int chunkX, int chunkZ) {
        final int minBx = chunkX << 4;
        final int minBz = chunkZ << 4;
        final int maxBx = minBx + 15;
        final int maxBz = minBz + 15;
        final int minY = region.getMinimumPoint().y();
        final int maxY = region.getMaximumPoint().y();

        if (region instanceof CuboidRegion) {
            // Fast, shape-exact path for the overwhelmingly common selection type.
            final BlockVector3 rMin = region.getMinimumPoint();
            final BlockVector3 rMax = region.getMaximumPoint();
            final int x1 = Math.max(rMin.x(), minBx);
            final int z1 = Math.max(rMin.z(), minBz);
            final int x2 = Math.min(rMax.x(), maxBx);
            final int z2 = Math.min(rMax.z(), maxBz);
            if (x1 > x2 || z1 > z2) {
                return null;
            }
            return new CuboidRegion(world, BlockVector3.at(x1, minY, z1), BlockVector3.at(x2, maxY, z2));
        }

        // General, shape-exact path: a cuboid clamped to the chunk whose membership defers to the original region.
        final CuboidRegion chunkBounds = new CuboidRegion(
                world,
                BlockVector3.at(minBx, minY, minBz),
                BlockVector3.at(maxBx, maxY, maxBz)
        );
        return new IntersectionRegion(world, region, chunkBounds);
    }

    /**
     * A region whose membership is the intersection of two regions while its iteration/bounds track {@code primary} (always the
     * chunk-bounded cuboid here). Used so a per-chunk dispatch logs/edits exactly the positions present in both the original
     * selection and the chunk column.
     */
    private static final class IntersectionRegion extends com.sk89q.worldedit.regions.AbstractRegion {

        private final Region original;
        private final CuboidRegion primary;

        private IntersectionRegion(World world, Region original, CuboidRegion primary) {
            super(world);
            this.original = original;
            this.primary = primary;
        }

        @Override
        public BlockVector3 getMinimumPoint() {
            return primary.getMinimumPoint();
        }

        @Override
        public BlockVector3 getMaximumPoint() {
            return primary.getMaximumPoint();
        }

        @Override
        public void expand(BlockVector3... changes) throws com.sk89q.worldedit.regions.RegionOperationException {
            throw new com.sk89q.worldedit.regions.RegionOperationException("Cannot expand an intersection region");
        }

        @Override
        public void contract(BlockVector3... changes) throws com.sk89q.worldedit.regions.RegionOperationException {
            throw new com.sk89q.worldedit.regions.RegionOperationException("Cannot contract an intersection region");
        }

        @Override
        public boolean contains(BlockVector3 position) {
            return primary.contains(position) && original.contains(position);
        }

        @Override
        public java.util.Iterator<BlockVector3> iterator() {
            return com.google.common.collect.Iterators.filter(primary.iterator(), original::contains);
        }
    }
}
