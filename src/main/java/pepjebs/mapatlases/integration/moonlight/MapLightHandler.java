package pepjebs.mapatlases.integration.moonlight;

import com.mojang.blaze3d.platform.NativeImage;
import net.mehvahdjukaar.moonlight.api.map.CustomMapData;
import net.mehvahdjukaar.moonlight.api.map.MapDataRegistry;
import net.mehvahdjukaar.moonlight.api.util.math.colors.RGBColor;
import net.mehvahdjukaar.supplementaries.configs.ClientConfigs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import pepjebs.mapatlases.MapAtlasesMod;
import pepjebs.mapatlases.config.MapAtlasesConfig;
import pepjebs.mapatlases.utils.ActivationLocation;

import java.util.Objects;


public class MapLightHandler {

    public static void init() {
    }

    protected static int DITHERING = 1;

    public static final CustomMapData.Type<LightData> LIGHT_DATA =
            MapDataRegistry.registerCustomMapSavedData(MapAtlasesMod.res("light_data"), LightData::new);

    public static LightData getLightData(MapItemSavedData data) {
        return LIGHT_DATA.get(data);
    }

    private static class Counter implements CustomMapData.DirtyCounter {
        private int minDirtyX = 0;
        private int maxDirtyX = 127;
        private int minDirtyZ = 0;
        private int maxDirtyZ = 127;
        private boolean posDirty = true;

        public void markDirty(int x, int z) {
            if (this.posDirty) {
                this.minDirtyX = Math.min(this.minDirtyX, x);
                this.minDirtyZ = Math.min(this.minDirtyZ, z);
                this.maxDirtyX = Math.max(this.maxDirtyX, x);
                this.maxDirtyZ = Math.max(this.maxDirtyZ, z);
            } else {
                //reset
                this.posDirty = true;
                this.minDirtyX = x;
                this.minDirtyZ = z;
                this.maxDirtyX = x;
                this.maxDirtyZ = z;
            }
        }

        @Override
        public boolean isDirty() {
            return posDirty;
        }

        @Override
        public void clearDirty() {
            this.posDirty = false;
            this.minDirtyX = 0;
            this.minDirtyZ = 0;
            this.maxDirtyX = 0;
            this.maxDirtyZ = 0;
        }

    }

    public static class LightData implements CustomMapData<Counter> {

        private static final String LIGHTMAP_TAG = "lightmap";
        public static final String MIN_X = "min_x";
        public static final String MAX_X = "max_x";
        public static final String MIN_Z = "min_z";
        private byte[][] data = null;

        private int getEntry(int x, int z) {
            if (data == null) return 0;
            if (x < 0 || x >= 128 || z < 0 || z >= 128) {
                return 0; //error
            }
            if (data[x] != null) {
                return Byte.toUnsignedInt(data[x][z]);
            }
            return 0;
        }

        private void addEntry(MapItemSavedData md, int x, int z, int packedLight) {
            if (data == null) {
                data = new byte[128][];
            }
            if (data[x] == null) data[x] = new byte[128];
            data[x][z] = (byte) (packedLight);
            this.setDirty(md, counter -> counter.markDirty(x, z));
        }

        @Override
        public void load(CompoundTag tag) {
            if (tag.contains(LIGHTMAP_TAG)) {
                CompoundTag t = tag.getCompound(LIGHTMAP_TAG);

                int minX = 0;
                if (t.contains(MIN_X)) minX = t.getInt(MIN_X);
                int maxX = 127;
                if (t.contains(MAX_X)) maxX = t.getInt(MAX_X);
                int minZ = 0;
                if (t.contains(MIN_Z)) minZ = t.getInt(MIN_Z);

                for (int x = minX; x <= maxX; x++) {
                    byte[] rowData = t.getByteArray("pos_" + x);
                    if (data == null) {
                        data = new byte[128][];
                    }
                    if (data[x] == null) {
                        data[x] = new byte[128];
                    }
                    System.arraycopy(rowData, 0, data[x], minZ, rowData.length);
                }
            }
        }

        private void savePatch(CompoundTag tag, int minX, int maxX, int minZ, int maxZ,
                               boolean pos) {

            if (pos && data != null) {
                CompoundTag t = new CompoundTag();
                if (minX != 0) t.putInt(MIN_X, minX);
                if (maxX != 127) t.putInt(MAX_X, maxX);
                if (minZ != 0) t.putInt(MIN_Z, minZ);

                for (int x = minX; x <= maxX; x++) {
                    if (data[x] != null) {
                        byte[] rowData = new byte[maxZ - minZ + 1];

                        System.arraycopy(data[x], minZ, rowData, 0, rowData.length);
                        t.putByteArray("pos_" + x, rowData);
                    }
                }
                tag.put(LIGHTMAP_TAG, t);
            }
        }

        @Override
        public void save(CompoundTag tag) {
            // save all
            savePatch(tag, 0, 127, 0, 127, true);
        }

        @Override
        public void saveToUpdateTag(CompoundTag tag, Counter dc) {
            this.savePatch(tag, dc.minDirtyX, dc.maxDirtyX, dc.minDirtyZ, dc.maxDirtyZ, dc.posDirty);
        }

        @Override
        public void loadUpdateTag(CompoundTag tag) {
            load(tag);
        }

        @Override
        public boolean persistOnCopyOrLock() {
            return false;
        }

        @Override
        public Type<?> getType() {
            return LIGHT_DATA;
        }

        @Override
        public Counter createDirtyCounter() {
            return new Counter();
        }

        public void setLightLevel(int x, int z, int packedLight, MapItemSavedData data) {
            if (packedLight != 0) {
                //dither biomes
                if (!Objects.equals(this.getEntry(x, z), packedLight)) {
                    this.addEntry(data, x, z, packedLight);
                }
            } else {
                //remove unneded stufff
                if (this.data != null && this.data[x] != null && this.data[x][z] != 0) {
                    this.data[x][z] = 0;
                    this.setDirty(data, counter -> counter.markDirty(x, z));
                    for (var b : this.data[x]) {
                        if (b != 0) return;
                    }
                    this.data[x] = null;
                }
            }
        }


        @OnlyIn(Dist.CLIENT)
        public void processTexture(NativeImage texture, int startX, int startY) {
             if (!MapAtlasesConfig.lightMap .get()) return;
            for (int x = 0; x < 128; ++x) {
                for (int z = 0; z < 128; ++z) {
                    int light = getEntry(x, z);
                    if (light == 0) continue;

                    int pX = startX + x;
                    int pY = startY + z;
                    var c = getLitUp(texture.getPixelRGBA(pX, pY), light);
                    texture.setPixelRGBA(pX, pY, c.toInt());
                }
            }
        }
    }

    public static RGBColor getLitUp(int color, int light) {
        float v = (light / 15f) * 0.7f;
        RGBColor rgbColor = new RGBColor(0xffc0e2ee);
        float r = 1;
        return new RGBColor(color).multiply((r + rgbColor.red() * v), (r + rgbColor.green() * v), (r + rgbColor.green() * v), r);
    }

}