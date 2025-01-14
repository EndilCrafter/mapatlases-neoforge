package pepjebs.mapatlases.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pepjebs.mapatlases.MapAtlasesMod;

@Mixin(value = MapItem.class, priority = 1200)
public class MapItemMixin {

    @WrapOperation(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    public LevelChunk reduceUpdateNonGeneratedChunks(Level instance, int chunkX, int chunkZ,
                                                     Operation<LevelChunk> original,
                                                     @Local(ordinal = 8) int distance,
                                                     @Local(ordinal = 5) int range,
                                                     @Local(ordinal = 0) int scale) {
        //also checks the range early
        if (MapAtlasesMod.rangeCheck(distance, range, scale)) {
            var c = instance.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (c instanceof LevelChunk lc) {
                //original
                return lc;
            }
        }
        //return empty
        return new EmptyLevelChunk(instance, new ChunkPos(chunkX, chunkZ),
                instance.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.FOREST));
    }


    // fixes issues with vanilla maps where first strips takes ages to update by incrementing step after map calculation
    @Inject(method = "update", at = @At(value = "NEW", target = "()Lnet/minecraft/core/BlockPos$MutableBlockPos;",
            ordinal = 0), require = 1)
    public void startFromZeroStep(Level level, Entity viewer, MapItemSavedData data, CallbackInfo ci,
                                  @Local MapItemSavedData.HoldingPlayer holdingPlayer, @Share("needsPostIncrement") LocalRef<MapItemSavedData.HoldingPlayer> needsPostInc) {
        holdingPlayer.step--;
        needsPostInc.set(holdingPlayer);
    }

    @Inject(method = "update", at = @At(value = "RETURN"))
    public void doPostIncrement(Level level, Entity viewer, MapItemSavedData data, CallbackInfo ci,
                                @Share("needsPostIncrement") LocalRef<MapItemSavedData.HoldingPlayer> needsPostInc) {
        MapItemSavedData.HoldingPlayer holdingPlayer = needsPostInc.get();
        if (holdingPlayer != null) {
            holdingPlayer.step++;
            needsPostInc.set(null);
        }
    }

}
