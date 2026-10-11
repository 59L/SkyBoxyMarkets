package dev.skyboxy.market;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** Main-thread incremental scan; never reads an unloaded chunk or modifies the world. */
final class MarketScanner {
    private record BoxKey(String world, int x, int y, int z, int width, int height, int length) {}

    private static final class Discovery {
        final BoxKey box;
        final Map<String, Set<Integer>> rotationsByStyle = new LinkedHashMap<>();

        Discovery(BoxKey box) { this.box = box; }
    }

    private record PendingMatch(StyleTemplates.Template template, int x, int y, int z, int rotation,
                                TemplateMatcher.MatchCursor cursor) {}
    private record Candidate(BoxKey key, Booth booth) {}

    private final SkyBoxyMarket plugin;
    private BukkitTask running;
    private long scanVolume;
    private long scanChecked;
    private String lastResult="No marketplace scan has run.";

    MarketScanner(SkyBoxyMarket plugin) { this.plugin = plugin; }

    boolean running() { return running != null; }

    String progress() {
        if (!running()) return lastResult;
        long percent = scanVolume == 0 ? 0 : Math.min(100, scanChecked * 100 / scanVolume);
        return "Marketplace scan: " + scanChecked + "/" + scanVolume + " positions (" + percent + "%).";
    }

    void cancel() {
        if (running != null) {
            running.cancel();
            running = null;
        }
    }

    boolean cancel(Player initiator) {
        if (running == null) return false;
        cancel();
        lastResult="Marketplace scan cancelled after "+scanChecked+" of "+scanVolume+" positions.";
        plugin.tell(initiator, "&e"+lastResult);
        return true;
    }

    ProtectedRegion region(World world) {
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        return manager == null ? null : manager.getRegion(plugin.getConfig().getString("market.region", "marketmain"));
    }

    boolean inMain(org.bukkit.Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().getName().equals(plugin.getConfig().getString("market.world", "world"))) return false;
        ProtectedRegion market = region(location.getWorld());
        return market != null && market.contains(BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    void scan(Player initiator) {
        if (running()) {
            plugin.tell(initiator, "&eA marketplace scan is already running. Use the scan status or cancel action.");
            return;
        }
        World world = Bukkit.getWorld(plugin.getConfig().getString("market.world", "world"));
        if (world == null) {
            plugin.tell(initiator, "&cMarket world is not loaded.");
            return;
        }
        ProtectedRegion region = region(world);
        if (region == null) {
            plugin.tell(initiator, "&cWorldGuard region 'marketmain' could not be found in " + world.getName());
            return;
        }
        List<StyleTemplates.Template> templates = new ArrayList<>(plugin.templates.enabled());
        if (templates.isEmpty()) {
            plugin.tell(initiator, "&cNo valid enabled schematic templates.");
            return;
        }

        BlockVector3 minimum = region.getMinimumPoint();
        BlockVector3 maximum = region.getMaximumPoint();
        int minX = minimum.x(), minY = Math.max(world.getMinHeight(), minimum.y()), minZ = minimum.z();
        int maxX = maximum.x(), maxY = Math.min(world.getMaxHeight() - 1, maximum.y()), maxZ = maximum.z();
        long volume = ((long) maxX - minX + 1) * ((long) maxY - minY + 1) * ((long) maxZ - minZ + 1);
        if (volume <= 0 || volume > plugin.getConfig().getLong("market.scan-max-volume", 15_000_000)) {
            plugin.tell(initiator, "&cScan volume " + volume + " exceeds the configured safety limit. Narrow marketmain or raise market.scan-max-volume.");
            return;
        }

        scanVolume = volume;
        scanChecked = 0;
        lastResult="Marketplace scan started; use /market scan status to check progress.";
        final int[] position = {minX, minY, minZ};
        final int[] added = {0}, duplicates = {0}, chunksSkipped = {0}, overlap = {0}, ambiguous = {0}, failures = {0};
        final long[] checked = {0};
        final int positionBudget = Math.max(100, plugin.getConfig().getInt("market.scan-blocks-per-tick", 1600));
        final int comparisonBudget = Math.max(1, plugin.getConfig().getInt("market.scan-comparisons-per-tick", 12000));
        final int registrationBudget = Math.max(1, plugin.getConfig().getInt("market.scan-registrations-per-tick", 20));
        final boolean abortOnUnloaded=plugin.getConfig().getString("market.scan-unloaded-policy","skip").equalsIgnoreCase("abort");
        final Deque<PendingMatch> pendingMatches = new ArrayDeque<>();
        final Map<BoxKey, Discovery> discoveries = new LinkedHashMap<>();
        final List<Booth> registrationQueue = new ArrayList<>();
        final int[] registrationIndex = {0};
        final boolean[] registrationPrepared = {false};

        plugin.tell(initiator, "&aScanning " + volume + " positions across " + templates.size()
                + " styles. Work is limited per tick; use scan status or cancel from the admin menu.");
        running = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ScanWorkBudget work = new ScanWorkBudget(comparisonBudget);
            int positionsThisTick = 0;

            while (work.remaining() > 0) {
                if (!pendingMatches.isEmpty()) {
                    PendingMatch candidate = pendingMatches.peekFirst();
                    boolean[] candidateChunkUnloaded={false};
                    int used = candidate.cursor().advance((x, y, z) -> {
                        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                            chunksSkipped[0]++;
                            candidateChunkUnloaded[0]=true;
                            return "__unloaded_chunk__";
                        }
                        return world.getBlockAt(x, y, z).getBlockData().getAsString();
                    }, work.remaining());
                    if(candidateChunkUnloaded[0]&&abortOnUnloaded){
                        cancel();lastResult="Marketplace scan cancelled because a required chunk unloaded at "+candidate.x()+","+candidate.z()+".";
                        plugin.tell(initiator,"&c"+lastResult);return;
                    }
                    for (int count = 0; count < used; count++) work.tryConsume();
                    if (candidate.cursor().done()) {
                        pendingMatches.removeFirst();
                        if (candidate.cursor().matches()) remember(discoveries, world.getName(), candidate);
                    } else if (used == 0) {
                        break;
                    }
                    continue;
                }

                if (registrationPrepared[0] || positionsThisTick >= positionBudget || position[1] > maxY) break;

                int px = position[0], py = position[1], pz = position[2];
                advance(position, minX, maxX, minY, maxY, minZ, maxZ);
                positionsThisTick++;
                checked[0]++;
                scanChecked = checked[0];

                if (!world.isChunkLoaded(px >> 4, pz >> 4)) {
                    chunksSkipped[0]++;
                    if(abortOnUnloaded){cancel();lastResult="Marketplace scan cancelled because chunk "+(px>>4)+","+(pz>>4)+" is unloaded.";plugin.tell(initiator,"&c"+lastResult);return;}
                    continue;
                }
                if (!region.contains(BlockVector3.at(px, py, pz))) continue;
                if (!work.tryConsume()) break;
                String material = world.getBlockAt(px, py, pz).getType().name().toLowerCase(Locale.ROOT);
                if (TemplateMatcher.isAir(material)) continue;

                for (StyleTemplates.Template template : templates) {
                    if (!template.anchor.material().name().toLowerCase(Locale.ROOT).equals(material)) continue;
                    StyleTemplates.Cell anchor = template.anchor;
                    for (int rotation = 0; rotation < 4; rotation++) {
                        int originX = px - template.rotateX(anchor.x(), anchor.z(), rotation);
                        int originY = py - anchor.y();
                        int originZ = pz - template.rotateZ(anchor.x(), anchor.z(), rotation);
                        if (!insideRegion(region, template, originX, originY, originZ, rotation)) continue;
                        if (!loaded(world, template, originX, originZ, rotation)) {
                            chunksSkipped[0]++;
                            continue;
                        }
                        pendingMatches.addLast(new PendingMatch(template, originX, originY, originZ, rotation,
                                template.matcher.cursor(originX, originY, originZ, rotation,
                                        plugin.getConfig().getBoolean("market.scan-match-air", true))));
                    }
                }
            }

            if (position[1] > maxY && pendingMatches.isEmpty() && !registrationPrepared[0]) {
                prepareRegistration(discoveries, registrationQueue, duplicates, overlap, ambiguous);
                registrationPrepared[0] = true;
            }

            if (registrationPrepared[0]) {
                int registrations = 0;
                while (registrationIndex[0] < registrationQueue.size() && registrations < registrationBudget) {
                    Booth booth = registrationQueue.get(registrationIndex[0]++);
                    registrations++;
                    try {
                        plugin.db.save(booth);
                        plugin.registerBooth(booth);
                        plugin.holograms.refresh(booth);
                        added[0]++;
                    } catch (Exception exception) {
                        failures[0]++;
                        plugin.getLogger().severe("Cannot register discovered booth at " + booth.world + " "
                                + booth.x + "," + booth.y + "," + booth.z + ": " + exception);
                    }
                }
                if (registrationIndex[0] >= registrationQueue.size()) {
                    cancel();
                    lastResult="Scan finished: "+added[0]+" new, "+duplicates[0]+" known, "+overlap[0]+" overlapping, "+ambiguous[0]+" ambiguous, "+failures[0]
                            +" failed to save; "+chunksSkipped[0]+" positions/candidates skipped because chunks were unloaded.";
                    plugin.tell(initiator, "&a"+lastResult);
                    plugin.db.audit(initiator == null ? "CONSOLE" : initiator.getUniqueId().toString(), "scan",
                            "added=" + added[0] + " checked=" + checked[0] + " failures=" + failures[0]);
                }
            }
        }, 1, 1);
    }

    private void remember(Map<BoxKey, Discovery> discoveries, String world, PendingMatch match) {
        StyleTemplates.Template template = match.template();
        BoxKey key = new BoxKey(world, match.x(), match.y(), match.z(), template.rotatedWidth(match.rotation()),
                template.height, template.rotatedLength(match.rotation()));
        discoveries.computeIfAbsent(key, Discovery::new)
                .rotationsByStyle.computeIfAbsent(template.style.id, ignored -> new TreeSet<>())
                .add(match.rotation());
    }

    private void prepareRegistration(Map<BoxKey, Discovery> discoveries, List<Booth> registrationQueue,
                                     int[] duplicates, int[] overlap, int[] ambiguous) {
        List<Candidate> candidates = new ArrayList<>();
        for (Discovery discovery : discoveries.values()) {
            if (discovery.rotationsByStyle.size() != 1) {
                ambiguous[0]++;
                continue;
            }
            Map.Entry<String, Set<Integer>> style = discovery.rotationsByStyle.entrySet().iterator().next();
            BoxKey key = discovery.box;
            Booth booth = new Booth(UUID.randomUUID().toString(), key.world(), style.getKey(), key.x(), key.y(), key.z(),
                    key.width(), key.height(), key.length(), style.getValue().stream().min(Integer::compareTo).orElse(0));

            Booth prior = plugin.booths.stream().filter(existing -> sameBox(existing, key)).findFirst().orElse(null);
            if (prior != null) {
                duplicates[0]++;
                continue;
            }
            if (plugin.booths.stream().anyMatch(existing -> existing.overlaps(booth))) {
                overlap[0]++;
                continue;
            }
            candidates.add(new Candidate(key, booth));
        }

        candidates.sort(Comparator.comparing((Candidate candidate) -> candidate.key().world())
                .thenComparingInt(candidate -> candidate.key().x())
                .thenComparingInt(candidate -> candidate.key().y())
                .thenComparingInt(candidate -> candidate.key().z()));
        Set<BoxKey> conflicts = new HashSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate first = candidates.get(i);
            for (int j = i + 1; j < candidates.size(); j++) {
                Candidate second = candidates.get(j);
                if (!first.key().world().equals(second.key().world())) break;
                if (second.key().x() >= first.key().x() + first.key().width()) break;
                if (first.booth().overlaps(second.booth())) {
                    conflicts.add(first.key());
                    conflicts.add(second.key());
                }
            }
        }
        for (Candidate candidate : candidates) {
            if (conflicts.contains(candidate.key())) overlap[0]++;
            else registrationQueue.add(candidate.booth());
        }
    }

    private boolean sameBox(Booth booth, BoxKey key) {
        return booth.world.equals(key.world()) && booth.x == key.x() && booth.y == key.y() && booth.z == key.z()
                && booth.width == key.width() && booth.height == key.height() && booth.length == key.length();
    }

    private void advance(int[] position, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        if (++position[0] > maxX) {
            position[0] = minX;
            if (++position[2] > maxZ) {
                position[2] = minZ;
                position[1]++;
            }
        }
    }

    private boolean insideRegion(ProtectedRegion region, StyleTemplates.Template template, int x, int y, int z, int rotation) {
        int width = template.rotatedWidth(rotation), length = template.rotatedLength(rotation);
        if (y < region.getMinimumPoint().y() || y + template.height - 1 > region.getMaximumPoint().y()) return false;
        if (!plugin.getConfig().getBoolean("market.scan-verify-all-blocks-in-region", true)) {
            return region.contains(BlockVector3.at(x, y, z))
                    && region.contains(BlockVector3.at(x + width - 1, y + template.height - 1, z + length - 1));
        }
        for (int blockX = 0; blockX < width; blockX++) {
            for (int blockZ = 0; blockZ < length; blockZ++) {
                if (!region.contains(BlockVector3.at(x + blockX, y, z + blockZ))
                        || !region.contains(BlockVector3.at(x + blockX, y + template.height - 1, z + blockZ))) return false;
            }
        }
        return true;
    }

    private boolean loaded(World world, StyleTemplates.Template template, int x, int z, int rotation) {
        int maxChunkX = (x + template.rotatedWidth(rotation) - 1) >> 4;
        int maxChunkZ = (z + template.rotatedLength(rotation) - 1) >> 4;
        for (int chunkX = x >> 4; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = z >> 4; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }
}
