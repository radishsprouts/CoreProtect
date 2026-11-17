package net.coreprotect.web.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web UI から送られてくる検索・操作条件。
 */
public class LookupQuery {
    private static final Pattern TIME_TOKEN = Pattern.compile("(\\d+)(mo|[smhdwy])", Pattern.CASE_INSENSITIVE);

    public static final Set<Integer> CONTAINER_ACTION_IDS;
    public static final Set<Integer> INVENTORY_ACTION_IDS;
    public static final Set<Integer> TEXTUAL_ACTION_IDS;

    static {
        Set<Integer> c = new HashSet<>();
        c.add(4);
        c.add(5);
        CONTAINER_ACTION_IDS = Collections.unmodifiableSet(c);

        Set<Integer> inv = new HashSet<>();
        inv.add(ActionType.INVENTORY.id());
        INVENTORY_ACTION_IDS = Collections.unmodifiableSet(inv);

        Set<Integer> t = new HashSet<>();
        t.add(ActionType.CHAT.id());
        t.add(ActionType.COMMAND.id());
        t.add(ActionType.SESSION.id());
        t.add(ActionType.USERNAME.id());
        TEXTUAL_ACTION_IDS = Collections.unmodifiableSet(t);
    }

    private static final int GLOBAL_SEARCH_RADIUS = 30_000_000;

    public int timeSeconds = 86400;
    public List<String> users = new ArrayList<>();
    public List<String> excludeUsers = new ArrayList<>();
    public List<String> blockNames = new ArrayList<>();
    public List<String> excludeBlockNames = new ArrayList<>();
    public List<Integer> actionIds = new ArrayList<>();
    public int radius = 0;
    public String world;
    public Double x;
    public Double y;
    public Double z;
    public int offset = 0;
    public int rowCount = 50;

    public final List<String> warnings = new ArrayList<>();

    public static int parseTimeToSeconds(String raw, int fallbackSeconds) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallbackSeconds;
        }

        if (raw.matches("\\d+")) {
            return Integer.parseInt(raw);
        }

        Matcher matcher = TIME_TOKEN.matcher(raw);
        int total = 0;
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            int amount = Integer.parseInt(matcher.group(1));
            total += amount * unitSeconds(matcher.group(2).toLowerCase());
        }

        return matched ? total : fallbackSeconds;
    }

    private static int unitSeconds(String unit) {
        switch (unit) {
            case "s":
                return 1;
            case "m":
                return 60;
            case "h":
                return 3600;
            case "d":
                return 86400;
            case "w":
                return 604800;
            case "mo":
                return 2592000;
            case "y":
                return 31536000;
            default:
                return 1;
        }
    }

    public Location toLocation() {
        if (world == null || x == null || y == null || z == null) {
            return null;
        }

        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            warnings.add("ワールド '" + world + "' が見つかりません。範囲指定なしで検索します。");
            return null;
        }

        return new Location(bukkitWorld, x, y, z);
    }

    public static final class LocationTarget {
        private final Location location;
        private final int radius;

        public LocationTarget(Location location, int radius) {
            this.location = location;
            this.radius = radius;
        }

        public Location location() {
            return location;
        }

        public int radius() {
            return radius;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LocationTarget that = (LocationTarget) o;
            return radius == that.radius && Objects.equals(location, that.location);
        }

        @Override
        public int hashCode() {
            return Objects.hash(location, radius);
        }
    }

    public List<LocationTarget> locationTargets() {
        Location location = toLocation();
        if (location != null) {
            return Collections.singletonList(new LocationTarget(location, radius));
        }

        List<LocationTarget> targets = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            targets.add(new LocationTarget(new Location(world, 0, 64, 0), GLOBAL_SEARCH_RADIUS));
        }
        return targets;
    }

    public List<List<Integer>> actionIdBuckets() {
        List<Integer> container = new ArrayList<>();
        List<Integer> inventory = new ArrayList<>();
        List<Integer> other = new ArrayList<>();
        for (Integer id : actionIds) {
            if (CONTAINER_ACTION_IDS.contains(id)) {
                container.add(id);
            } else if (INVENTORY_ACTION_IDS.contains(id)) {
                inventory.add(id);
            } else {
                other.add(id);
            }
        }

        List<List<Integer>> buckets = new ArrayList<>();
        if (!container.isEmpty()) {
            buckets.add(container);
        }
        if (!inventory.isEmpty()) {
            buckets.add(inventory);
        }
        if (!other.isEmpty()) {
            buckets.add(other);
        }
        if (buckets.isEmpty()) {
            buckets.add(actionIds);
        }
        return buckets;
    }

    public static final class QueryTarget {
        private final Location location;
        private final int radius;
        private final List<Integer> actionIds;

        public QueryTarget(Location location, int radius, List<Integer> actionIds) {
            this.location = location;
            this.radius = radius;
            this.actionIds = actionIds;
        }

        public Location location() {
            return location;
        }

        public int radius() {
            return radius;
        }

        public List<Integer> actionIds() {
            return actionIds;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            QueryTarget that = (QueryTarget) o;
            return radius == that.radius && Objects.equals(location, that.location) && Objects.equals(actionIds, that.actionIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(location, radius, actionIds);
        }
    }

    public List<QueryTarget> queryTargets() {
        List<QueryTarget> targets = new ArrayList<>();
        for (LocationTarget location : locationTargets()) {
            for (List<Integer> bucket : actionIdBuckets()) {
                targets.add(new QueryTarget(location.location(), location.radius(), bucket));
            }
        }
        return targets;
    }

    public boolean isContainerOnly() {
        if (actionIds.isEmpty()) {
            return false;
        }
        for (Integer id : actionIds) {
            if (!CONTAINER_ACTION_IDS.contains(id)) {
                return false;
            }
        }
        return true;
    }

    public boolean isInventoryOnly() {
        if (actionIds.isEmpty()) {
            return false;
        }
        for (Integer id : actionIds) {
            if (!INVENTORY_ACTION_IDS.contains(id)) {
                return false;
            }
        }
        return true;
    }

    public boolean isTextualOnly() {
        if (actionIds.isEmpty()) {
            return false;
        }
        for (Integer id : actionIds) {
            if (!TEXTUAL_ACTION_IDS.contains(id)) {
                return false;
            }
        }
        return true;
    }

    public List<Object> resolveBlocks(List<String> names) {
        List<Object> resolved = new ArrayList<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                resolved.add(material);
                continue;
            }

            try {
                resolved.add(EntityType.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException e) {
                warnings.add("'" + name + "' はブロック名/エンティティ名として認識できませんでした。無視します。");
            }
        }

        return resolved;
    }
}
