package pepjebs.mapatlases.lifecycle;

import com.mojang.datafixers.util.Pair;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import org.joml.Vector2i;
import pepjebs.mapatlases.MapAtlasesMod;
import pepjebs.mapatlases.capabilities.MapCollectionCap;
import pepjebs.mapatlases.capabilities.MapKey;
import pepjebs.mapatlases.client.MapAtlasesClient;
import pepjebs.mapatlases.config.MapAtlasesClientConfig;
import pepjebs.mapatlases.config.MapAtlasesConfig;
import pepjebs.mapatlases.integration.SupplementariesCompat;
import pepjebs.mapatlases.item.MapAtlasItem;
import pepjebs.mapatlases.utils.MapAtlasesAccessUtils;
import pepjebs.mapatlases.utils.Slice;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class MapAtlasesServerEvents {

    // Used to prevent Map creation spam consuming all Empty Maps on auto-create
    private static final ReentrantLock mutex = new ReentrantLock();

    // Holds the current MapItemSavedData ID for each player
    //maybe use weakhasmap with plauer
    @Deprecated(forRemoval = true)
    private static final WeakHashMap<Player, String> playerToActiveMapId = new WeakHashMap<>();
    private static final WeakHashMap<Player, HashMap<MapItemSavedData, MapUpdateTicket>> queues = new WeakHashMap<>();

    private static class MapUpdateTicket {
        private static final Comparator<MapUpdateTicket> COMPARATOR = Comparator.comparingDouble(MapUpdateTicket::getPriority);

        private final MapItemSavedData data;
        private int waitTime = 20; //set to 0 when this is updated. if not incremented each tick. we start with lowest for newly added entries
        private double lastDistance = 1000000;
        private double currentPriority; //bigger the better

        private MapUpdateTicket(MapItemSavedData data) {
            this.data = data;
        }

        public double getPriority() {
            return currentPriority;
        }

        public void updatePriority(int px, int pz) {
            this.waitTime++;
            double distSquared = Mth.lengthSquared(px - data.centerX, pz - data.centerZ);
            // Define weights for distance and waitTime
            double distanceWeight = 20; // Adjust this based on your preference
            double waitTimeWeight = 1; // Adjust this based on your preference

            // Calculate the priority using a weighted sum
            double deltaDist = distanceWeight * (lastDistance - distSquared); //for maps getting closer
            this.currentPriority = deltaDist + (waitTimeWeight * this.waitTime * this.waitTime);
            this.lastDistance = distSquared;
        }
    }

    @SubscribeEvent
    public static void mapAtlasesPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.side == LogicalSide.CLIENT) {
            //caches client stuff
            MapAtlasesClient.cachePlayerState(event.player);
        } else {
            ServerPlayer player = ((ServerPlayer) event.player);
            //not needed?
            //if (player.isRemoved() || player.isChangingDimension() || player.hasDisconnected()) continue;

            var server = player.server;
            ItemStack atlas = MapAtlasesAccessUtils.getAtlasFromPlayerByConfig(player);
            if (atlas.isEmpty()) return;
            Level level = player.level();
            MapCollectionCap maps = MapAtlasItem.getMaps(atlas, level);
            Slice slice = MapAtlasItem.getSelectedSlice(atlas, level.dimension());

            // sets new center map

            MapKey activeKey = MapKey.at(maps.getScale(), player, slice);

            int playX = player.blockPosition().getX();
            int playZ = player.blockPosition().getZ();
            byte scale = maps.getScale();
            int scaleWidth = (1 << scale) * 128;
            Set<Vector2i> discoveringEdges = getPlayerDiscoveringMapEdges(
                    activeKey.mapX(),
                    activeKey.mapZ(),
                    scaleWidth,
                    playX,
                    playZ,
                    slice.getDiscoveryReach()
            );

            // Update Map states & colors
            //these also include active map
            List<Pair<String, MapItemSavedData>> nearbyExistentMaps =
                    maps.filterSection(level.dimension(), slice, e -> discoveringEdges.stream()
                            .anyMatch(edge -> edge.x == e.centerX
                                    && edge.y == e.centerZ));

            Pair<String, MapItemSavedData> activeInfo = maps.select(activeKey);
            if (activeInfo == null) {
                // no map. we try creating a new one for this dimension
                maybeCreateNewMapEntry(player, atlas, maps, slice, Mth.floor(player.getX()),
                        Mth.floor(player.getZ()));
            }

            // updateColors is *easily* the most expensive function in the entire server tick
            // As a result, we will only ever call updateColors twice per tick (same as vanilla's limit)
            if (!nearbyExistentMaps.isEmpty()) {
                MapItemSavedData selected;
                if (MapAtlasesConfig.roundRobinUpdate.get()) {
                    selected = nearbyExistentMaps.get(server.getTickCount() % nearbyExistentMaps.size()).getSecond();
                    slice.updateMap(player, selected);

                } else {
                    for (int j = 0; j < MapAtlasesConfig.mapUpdatePerTick.get(); j++) {
                        selected = getMapToUpdate(nearbyExistentMaps, player);
                        slice.updateMap(player, selected);
                    }
                }
            }
            // update center one too but not each tick
            if (activeInfo != null && isTimeToUpdate(activeInfo.getSecond(), player, slice, 5, 20)) {
                slice.updateMap(player, activeInfo.getSecond());
            }
            if (activeInfo != null) nearbyExistentMaps.add(activeInfo);

            //TODO: old code called this for all maps. Isnt it enough to just call for the visible ones?
            //this also update banners and decorations so wen dont want to update stuff we cant see
            for (var mapInfo : nearbyExistentMaps) {
                MapAtlasesAccessUtils.updateMapDataAndSync(mapInfo, player, atlas);
                //if data has changed, packet will be sent
            }

            // Create new Map entries
            if (!MapAtlasesConfig.enableEmptyMapEntryAndFill.get() ||
                    MapAtlasItem.isLocked(atlas)) return;

            //TODO : this isnt accurate and can be improved
            if (isPlayerTooFarAway(activeKey, player, scaleWidth)) {
                maybeCreateNewMapEntry(player, atlas, maps ,slice, Mth.floor(player.getX()),
                        Mth.floor(player.getZ()));
            }
            //remove existing maps and tries to fill in remaining nones
            discoveringEdges.removeIf(e -> nearbyExistentMaps.stream().anyMatch(
                    d -> d.getSecond().centerX == e.x && d.getSecond().centerZ == e.y));
            for (var edge : discoveringEdges) {
                maybeCreateNewMapEntry(player, atlas, maps, slice, edge.x, edge.y);
            }

        }
    }

    //checks if pixel of this map has been filled at this position with random offset
    private static boolean isTimeToUpdate(MapItemSavedData data, Player player,
                                          Slice slice, int min, int max) {
        int i = 1 << data.scale;
        int range;
        if (slice != null && MapAtlasesMod.SUPPLEMENTARIES) {
            range =  (SupplementariesCompat.getSliceReach() / i);
        } else {
            range = 128 / i;
        }
        Level level = player.level();
        int rx = level.random.nextIntBetweenInclusive(-range, range);
        int rz = level.random.nextIntBetweenInclusive(-range, range);
        int x = (int) Mth.clamp((player.getX() + rx - data.centerX) / i + 64, 0, 127);
        int z = (int) Mth.clamp((player.getZ() + rz - data.centerZ) / i + 64, 0, 127);
        boolean filled = data.colors[x + z * 128] != 0;

        int interval = filled ? max : min;

        return level.getGameTime() % interval == 0;
    }

    private static MapItemSavedData getMapToUpdate(List<Pair<String, MapItemSavedData>> nearbyExistentMaps, ServerPlayer player) {
        var m = queues.computeIfAbsent(player, a -> new HashMap<>());
        Set<MapItemSavedData> existing = new HashSet<>();
        for (var v : nearbyExistentMaps) {
            var d = v.getSecond();
            existing.add(d);
            m.computeIfAbsent(d, a -> new MapUpdateTicket(d));
        }
        int px = player.getBlockX();
        int pz = player.getBlockZ();
        var it = m.entrySet().iterator();
        while (it.hasNext()) {
            var t = it.next();
            if (!existing.contains(t.getKey())) {
                it.remove();
            } else t.getValue().updatePriority(px, pz);
        }
        MapUpdateTicket selected = m.values().stream().max(MapUpdateTicket.COMPARATOR).orElseThrow();
        selected.waitTime = 0;
        return selected.data;
    }


    public static boolean isPlayerTooFarAway(
            MapKey key,
            Player player, int width
    ) {
        return Mth.square(key.mapX() - player.getX()) + Mth.square(key.mapZ() - player.getZ()) > width * width;
    }

    @Deprecated(forRemoval = true)
    private static String relayActiveMapIdToPlayerClient(
            Pair<String, MapItemSavedData> activeInfo,
            ServerPlayer player
    ) {
        String changedMapItemSavedData = null;
        String cachedMapId = playerToActiveMapId.get(player);
        if (activeInfo != null) {
            boolean addingPlayer = cachedMapId == null;
            // Players that pick up an atlas will need their MapItemSavedDatas initialized
            if (addingPlayer) {
                ItemStack stack = MapAtlasesAccessUtils.getAtlasFromPlayerByConfig(player);
                // MapAtlasItem.syncAndOpenGui(player, stack);
            }
            String currentMapId = activeInfo.getFirst();
            if (addingPlayer || !currentMapId.equals(cachedMapId)) {
                changedMapItemSavedData = cachedMapId;
                playerToActiveMapId.put(player, currentMapId);
                //   MapAtlasesNetowrking.sendToClientPlayer(player, new S2CSetActiveMapPacket(currentMapId));
            }
        } else if (cachedMapId != null) {
            playerToActiveMapId.put(player, null);

            // MapAtlasesNetowrking.sendToClientPlayer(player, new S2CSetActiveMapPacket("null"));
        }
        return changedMapItemSavedData;
    }

    //TODO: optimize
    private static void maybeCreateNewMapEntry(
            ServerPlayer player,
            ItemStack atlas,
            MapCollectionCap maps,
            Slice slice,
            int destX,
            int destZ
    ) {
        Level level = player.level();
        if (atlas.getTag() == null) {
            // If the Atlas is "inactive", give it a pity Empty Map count
            MapAtlasItem.setEmptyMaps(atlas, MapAtlasesConfig.pityActivationMapCount.get());
        }

        int emptyCount = MapAtlasItem.getEmptyMaps(atlas);
        boolean bypassEmptyMaps = !MapAtlasesConfig.requireEmptyMapsToExpand.get();
        boolean addedMap = false;
        if (!mutex.isLocked() && (emptyCount > 0 || player.isCreative() || bypassEmptyMaps)) {
            mutex.lock();

            // Make the new map
            if (!player.isCreative() && !bypassEmptyMaps) {
                //remove 1 map
                MapAtlasItem.increaseEmptyMaps(atlas, -1);
            }
            //validate height
            Integer height = slice.height();
            if (height != null && !maps.getHeightTree(player.level().dimension(), slice.type()).contains(height)) {
                int error = 1;
            }

            byte scale = maps.getScale();

            //TODO: create custom ones

            ItemStack newMap = slice.createNewMap(destX, destZ, scale, player.level());
            Integer mapId = MapItem.getMapId(newMap);

            if (mapId != null) {
                var newData = MapAtlasesAccessUtils.findMapFromId(level,mapId);
                // for custom map data to be sent immediately... crappy and hacky. TODO: change custom map data impl
                if (newData != null) {
                    MapAtlasesAccessUtils.updateMapDataAndSync(newData.getSecond(), mapId, player, newMap);
                }
                addedMap = maps.add(mapId, level);
            }
            mutex.unlock();
        }

        if (addedMap) {
            // Play the sound
            player.level().playSound(null, player.blockPosition(),
                    MapAtlasesMod.ATLAS_CREATE_MAP_SOUND_EVENT.get(),
                    SoundSource.PLAYERS, (float) (double) MapAtlasesClientConfig.soundScalar.get(), 1.0F);
        }
    }

    private static Set<Vector2i> getPlayerDiscoveringMapEdges(
            int xCenter,
            int zCenter,
            int width,
            int xPlayer,
            int zPlayer,
           int reach) {


        int halfWidth = width / 2;
        Set<Vector2i> results = new HashSet<>();
        for (int i = -1; i < 2; i++) {
            for (int j = -1; j < 2; j++) {
                if (i != 0 || j != 0) {
                    int qI = xCenter;
                    int qJ = zCenter;
                    if (i == -1 && xPlayer - reach <= xCenter - halfWidth) {
                        qI -= width;
                    } else if (i == 1 && xPlayer + reach >= xCenter + halfWidth) {
                        qI += width;
                    }
                    if (j == -1 && zPlayer - reach <= zCenter - halfWidth) {
                        qJ -= width;
                    } else if (j == 1 && zPlayer + reach >= zCenter + halfWidth) {
                        qJ += width;
                    }
                    // does not add duplicates this way
                    if (!(qI == xCenter && qJ == zCenter)) {
                        results.add(new Vector2i(qI, qJ));
                    }
                }
            }
        }
        return results;
    }
}