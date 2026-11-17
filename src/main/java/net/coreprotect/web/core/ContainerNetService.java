package net.coreprotect.web.core;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.api.LookupOptions;
import net.coreprotect.api.result.ContainerResult;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * コンテナの出し入れ（CoreProtect のコンテナトランザクションログ）を
 * プレイヤー×アイテムごとに集計し、「収納合計 - 取り出し合計」の純増減を算出する。
 * 型安全な ContainerResult を使用する。
 */
public class ContainerNetService {
    private static final int PAGE_SIZE = 1000;

    private final CoreProtect plugin;

    public ContainerNetService(CoreProtect plugin) {
        this.plugin = plugin;
    }

    public static final class Entry {
        private final String player;
        private final String item;
        private final long added;
        private final long removed;
        private final int txCount;
        private final int rolledBackTx;
        private final long firstSeen;
        private final long lastSeen;

        public Entry(String player, String item, long added, long removed, int txCount,
                     int rolledBackTx, long firstSeen, long lastSeen) {
            this.player = player;
            this.item = item;
            this.added = added;
            this.removed = removed;
            this.txCount = txCount;
            this.rolledBackTx = rolledBackTx;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }

        public String player() { return player; }
        public String item() { return item; }
        public long added() { return added; }
        public long removed() { return removed; }
        public int txCount() { return txCount; }
        public int rolledBackTx() { return rolledBackTx; }
        public long firstSeen() { return firstSeen; }
        public long lastSeen() { return lastSeen; }
        public long net() { return added - removed; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Entry entry = (Entry) o;
            return added == entry.added && removed == entry.removed && txCount == entry.txCount &&
                    rolledBackTx == entry.rolledBackTx && firstSeen == entry.firstSeen &&
                    lastSeen == entry.lastSeen && Objects.equals(player, entry.player) && Objects.equals(item, entry.item);
        }

        @Override
        public int hashCode() {
            return Objects.hash(player, item, added, removed, txCount, rolledBackTx, firstSeen, lastSeen);
        }
    }

    public static final class Result {
        private final List<Entry> entries;
        private final int scannedRows;
        private final boolean truncated;
        private final List<String> warnings;

        public Result(List<Entry> entries, int scannedRows, boolean truncated, List<String> warnings) {
            this.entries = entries;
            this.scannedRows = scannedRows;
            this.truncated = truncated;
            this.warnings = warnings;
        }

        public List<Entry> entries() { return entries; }
        public int scannedRows() { return scannedRows; }
        public boolean truncated() { return truncated; }
        public List<String> warnings() { return warnings; }
    }

    private static final class Accumulator {
        long added = 0;
        long removed = 0;
        int txCount = 0;
        int rolledBackTx = 0;
        long firstSeen = Long.MAX_VALUE;
        long lastSeen = Long.MIN_VALUE;

        void record(int action, int amount, boolean rolledBack, long time) {
            if (action == 1) { // 1 = 収納
                added += amount;
            } else { // 0 = 取出
                removed += amount;
            }
            txCount++;
            if (rolledBack) {
                rolledBackTx++;
            }
            if (time < firstSeen) {
                firstSeen = time;
            }
            if (time > lastSeen) {
                lastSeen = time;
            }
        }
    }

    private static final class AggKey {
        final String player;
        final String item;

        AggKey(String player, String item) {
            this.player = player;
            this.item = item;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AggKey)) return false;
            AggKey that = (AggKey) o;
            return Objects.equals(player, that.player) && Objects.equals(item, that.item);
        }

        @Override
        public int hashCode() {
            return Objects.hash(player, item);
        }
    }

    public CompletableFuture<Result> aggregate(LookupQuery query, int maxRows) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CoreProtectAPI api = plugin.getAPI();
                if (api == null || !api.isEnabled()) {
                    throw new IllegalStateException("CoreProtect API が利用できません。");
                }
                return doAggregate(api, query, maxRows);
            } catch (Exception e) {
                throw new RuntimeException("持ち出し集計中にエラーが発生しました: " + e.getMessage(), e);
            }
        });
    }

    private Result doAggregate(CoreProtectAPI api, LookupQuery query, int maxRows) {
        List<Object> blocks = query.resolveBlocks(query.blockNames);
        List<Object> excludeBlocks = query.resolveBlocks(query.excludeBlockNames);

        Map<AggKey, Accumulator> map = new LinkedHashMap<>();
        int scanned = 0;
        boolean truncated = false;
        List<String> warnings = new ArrayList<>(query.warnings);

        List<String> users = query.users.isEmpty() ? Collections.singletonList("#global") : query.users;

        for (LookupQuery.QueryTarget target : query.queryTargets()) {
            if (truncated) break;

            for (String user : users) {
                if (truncated) break;

                int offset = 0;
                while (true) {
                    LookupOptions.Builder builder = LookupOptions.builder()
                            .time(query.timeSeconds)
                            .limit(offset, PAGE_SIZE);

                    if (user != null && !user.isEmpty() && !user.equals("#global")) {
                        builder.user(user);
                    }
                    if (!query.excludeUsers.isEmpty()) {
                        builder.excludeUsers(query.excludeUsers);
                    }
                    if (target.location() != null) {
                        builder.radius(target.location(), target.radius());
                    }

                    if (!blocks.isEmpty()) {
                        List<Material> materials = new ArrayList<>();
                        for (Object obj : blocks) {
                            if (obj instanceof Material) materials.add((Material) obj);
                        }
                        if (!materials.isEmpty()) builder.includeMaterials(materials);
                    }
                    if (!excludeBlocks.isEmpty()) {
                        List<Material> materials = new ArrayList<>();
                        for (Object obj : excludeBlocks) {
                            if (obj instanceof Material) materials.add((Material) obj);
                        }
                        if (!materials.isEmpty()) builder.excludeMaterials(materials);
                    }

                    List<ContainerResult> page = api.containerLookup(builder.build());
                    if (page == null || page.isEmpty()) {
                        break;
                    }

                    for (ContainerResult result : page) {
                        String player = result.getPlayer();
                        if (isExcluded(player, query.excludeUsers)) {
                            continue;
                        }

                        Material material = result.getType();
                        String item = material != null ? material.name() : "AIR";
                        int action = result.getActionId();
                        int amount = result.getAmount();
                        boolean rolledBack = result.isRolledBack();
                        long time = result.getTimestamp() / 1000L;

                        AggKey key = new AggKey(player, item);
                        Accumulator acc = map.computeIfAbsent(key, k -> new Accumulator());
                        acc.record(action, amount, rolledBack, time);

                        scanned++;
                        if (scanned >= maxRows) {
                            truncated = true;
                            warnings.add("走査行数が上限（" + maxRows + "件）に達したため、集計を打ち切りました。");
                            break;
                        }
                    }

                    if (page.size() < PAGE_SIZE || truncated) {
                        break;
                    }
                    offset += PAGE_SIZE;
                }
            }
        }

        List<Entry> entries = new ArrayList<>(map.size());
        for (Map.Entry<AggKey, Accumulator> entry : map.entrySet()) {
            AggKey k = entry.getKey();
            Accumulator a = entry.getValue();
            entries.add(new Entry(k.player, k.item, a.added, a.removed, a.txCount,
                    a.rolledBackTx, a.firstSeen, a.lastSeen));
        }

        // 純流出が多い順（net が小さい＝マイナスが大きい順）に並べ替え
        entries.sort(Comparator.comparingLong(Entry::net));

        return new Result(entries, scanned, truncated, warnings);
    }

    private static boolean isExcluded(String player, List<String> excludeUsers) {
        if (player == null || excludeUsers == null || excludeUsers.isEmpty()) {
            return false;
        }
        for (String exclude : excludeUsers) {
            if (player.equalsIgnoreCase(exclude)) {
                return true;
            }
        }
        return false;
    }
}
