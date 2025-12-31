package malte0811.ferritecore.impl;

import com.google.common.base.Suppliers;
import it.unimi.dsi.fastutil.booleans.BooleanArrays;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import malte0811.ferritecore.ducks.BlockStateCacheAccess;
import malte0811.ferritecore.hash.ArrayVoxelShapeHash;
import malte0811.ferritecore.hash.VoxelShapeHash;
import malte0811.ferritecore.mixin.accessors.ArrayVSAccess;
import malte0811.ferritecore.mixin.accessors.SliceShapeAccess;
import malte0811.ferritecore.util.Constants;
import net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public class BlockStateCacheImpl {
    public static final Map<ArrayVSAccess, ArrayVSAccess> CACHE_COLLIDE = new Object2ObjectOpenCustomHashMap<>(
            ArrayVoxelShapeHash.INSTANCE
    );
    public static final Map<VoxelShape, Pair<VoxelShape, VoxelShape[]>> CACHE_PROJECT =
            new Object2ObjectOpenCustomHashMap<>(VoxelShapeHash.INSTANCE);
    public static final Map<boolean[], boolean[]> CACHE_FACE_STURDY = new Object2ObjectOpenCustomHashMap<>(
            BooleanArrays.HASH_STRATEGY
    );

    private static final Supplier<Function<BlockStateBase, BlockStateCacheAccess>> GET_CACHE = Suppliers.memoize(() -> {
        try {
            final String cacheName = Constants.PLATFORM_HOOKS.computeBlockstateCacheFieldName();
            final Field cacheField = BlockStateBase.class.getDeclaredField(cacheName);
            cacheField.setAccessible(true);
            MethodHandle getter = MethodHandles.lookup().unreflectGetter(cacheField);
            return state -> {
                try {
                    return (BlockStateCacheAccess) getter.invoke(state);
                } catch (Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
            };
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    });

    private static final ThreadLocal<BlockStateCacheAccess> LAST_CACHE = new ThreadLocal<>();

    public static void deduplicateCachePre(BlockStateBase state) {
        LAST_CACHE.set(GET_CACHE.get().apply(state));
    }

    public static void deduplicateCachePost(BlockStateBase state) {
        BlockStateCacheAccess newCache = GET_CACHE.get().apply(state);
        if (newCache != null) {
            final BlockStateCacheAccess oldCache = LAST_CACHE.get();
            deduplicateCollisionShape(newCache, oldCache);
            deduplicateRenderShapes(newCache, oldCache);
            deduplicateFaceSturdyArray(newCache, oldCache);
            // IMPORTANTE: Limpar a referência ThreadLocal imediatamente para evitar vazamento de memória
            LAST_CACHE.set(null);
            LAST_CACHE.remove(); 
        }
    }

    private static void deduplicateCollisionShape(
            BlockStateCacheAccess newCache, @Nullable BlockStateCacheAccess oldCache
    ) {
        VoxelShape dedupedCollisionShape;
        if (oldCache != null && VoxelShapeHash.INSTANCE.equals(
                oldCache.getCollisionShape(), newCache.getCollisionShape()
        )) {
            dedupedCollisionShape = oldCache.getCollisionShape();
        } else {
            dedupedCollisionShape = newCache.getCollisionShape();
            if (dedupedCollisionShape instanceof ArrayVSAccess access) {
                dedupedCollisionShape = (VoxelShape) CACHE_COLLIDE.computeIfAbsent(access, Function.identity());
            }
        }
        replaceInternals(dedupedCollisionShape, newCache.getCollisionShape());
        newCache.setCollisionShape(dedupedCollisionShape);
    }

    private static void deduplicateRenderShapes(
            BlockStateCacheAccess newCache, @Nullable BlockStateCacheAccess oldCache
    ) {
        // Otimização: Se occlusionShapes for nulo ou vazio, não fazer nada, economiza CPU.
        if (newCache.getOcclusionShapes() == null) return;

        final VoxelShape newRenderShape = getRenderShape(newCache.getOcclusionShapes());
        if (newRenderShape == null) {
            return;
        }
        Pair<VoxelShape, VoxelShape[]> dedupedRenderShapes = null;
        if (oldCache != null) {
            final VoxelShape oldRenderShape = getRenderShape(oldCache.getOcclusionShapes());
            if (VoxelShapeHash.INSTANCE.equals(newRenderShape, oldRenderShape)) {
                dedupedRenderShapes = Pair.of(oldRenderShape, oldCache.getOcclusionShapes());
            }
        }
        if (dedupedRenderShapes == null) {
            Pair<VoxelShape, VoxelShape[]> newPair = Pair.of(newRenderShape, newCache.getOcclusionShapes());
            dedupedRenderShapes = CACHE_PROJECT.putIfAbsent(newRenderShape, newPair);
            if (dedupedRenderShapes == null) {
                dedupedRenderShapes = newPair;
            }
        }
        replaceInternals(dedupedRenderShapes.getLeft(), newRenderShape);
        newCache.setOcclusionShapes(dedupedRenderShapes.getRight());
    }

    private static void deduplicateFaceSturdyArray(
            BlockStateCacheAccess newCache, @Nullable BlockStateCacheAccess oldCache
    ) {
        boolean[] dedupedFaceSturdy;
        if(oldCache != null && Arrays.equals(oldCache.getFaceSturdy(), newCache.getFaceSturdy())) {
            dedupedFaceSturdy = oldCache.getFaceSturdy();
        } else {
            // Usa cache global para arrays booleanos
            dedupedFaceSturdy = CACHE_FACE_STURDY.computeIfAbsent(newCache.getFaceSturdy(), Function.identity());
        }
        newCache.setFaceSturdy(dedupedFaceSturdy);
    }

    private static void replaceInternals(VoxelShape toKeep, VoxelShape toReplace) {
        if (toKeep instanceof ArrayVoxelShape keepArray && toReplace instanceof ArrayVoxelShape replaceArray) {
            replaceInternals(keepArray, replaceArray);
        }
    }

    public static void replaceInternals(ArrayVoxelShape toKeep, ArrayVoxelShape toReplace) {
        if (toKeep == toReplace) {
            return;
        }
        ArrayVSAccess toReplaceAccess = (ArrayVSAccess) toReplace;
        ArrayVSAccess toKeepAccess = (ArrayVSAccess) toKeep;
        // Substitui os internals para apontar para o objeto em cache
        toReplaceAccess.setXPoints(toKeepAccess.getXPoints());
        toReplaceAccess.setYPoints(toKeepAccess.getYPoints());
        toReplaceAccess.setZPoints(toKeepAccess.getZPoints());
        toReplaceAccess.setFaces(toKeepAccess.getFaces());
        toReplaceAccess.setShape(toKeepAccess.getShape());
    }

    @Nullable
    private static VoxelShape getRenderShape(@Nullable VoxelShape[] projected) {
        if (projected != null) {
            for (VoxelShape side : projected) {
                if (side instanceof SliceShapeAccess slice) {
                    return slice.getDelegate();
                }
            }
        }
        return null;
    }
}