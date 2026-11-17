package net.coreprotect.web.core;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.api.LookupOptions;
import net.coreprotect.api.result.BlockResult;
import net.coreprotect.api.result.ContainerResult;
import net.coreprotect.api.result.EntityResult;
import net.coreprotect.api.result.InventoryResult;
import net.coreprotect.api.result.MessageResult;
import net.coreprotect.api.result.SessionResult;
import net.coreprotect.api.result.SignResult;
import net.coreprotect.api.result.UsernameResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * CoreProtect 内部APIを型安全に直接呼び出す検索サービス。
 * メイン/リージョンスレッドを塞がないよう、非同期で実行する。
 */
public class LookupService {
    private final CoreProtect plugin;

    public LookupService(CoreProtect plugin) {
        this.plugin = plugin;
    }

    public static final class Outcome {
        private final List<LookupRow> rows;
        private final List<String> warnings;

        public Outcome(List<LookupRow> rows, List<String> warnings) {
            this.rows = rows;
            this.warnings = warnings;
        }

        public List<LookupRow> rows() {
            return rows;
        }

        public List<String> warnings() {
            return warnings;
        }
    }

    public CompletableFuture<Outcome> lookup(LookupQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CoreProtectAPI api = plugin.getAPI();
                if (api == null || !api.isEnabled()) {
                    throw new IllegalStateException("CoreProtect API が利用できません。");
                }
                List<LookupRow> rows = lookupMerged(api, query);
                return new Outcome(rows, query.warnings);
            } catch (Exception e) {
                throw new RuntimeException("検索処理中にエラーが発生しました: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<Outcome> session(String user, int timeSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CoreProtectAPI api = plugin.getAPI();
                if (api == null || !api.isEnabled()) {
                    throw new IllegalStateException("CoreProtect API が利用できません。");
                }
                List<String[]> rawRows = api.sessionLookup(user, timeSeconds);
                List<LookupRow> rows = new ArrayList<>();
                if (rawRows != null) {
                    for (String[] raw : rawRows) {
                        try {
                            CoreProtectAPI.ParseResult parsed = api.parseResult(raw);
                            rows.add(new LookupRow(
                                    parsed.getTime(),
                                    parsed.getPlayer(),
                                    ActionType.SESSION.id(),
                                    parsed.getActionString(),
                                    parsed.worldName(),
                                    parsed.getX(),
                                    parsed.getY(),
                                    parsed.getZ(),
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    Arrays.asList(raw)
                            ));
                        } catch (Exception e) {
                            rows.add(new LookupRow(0, null, Integer.MIN_VALUE, null, null, 0, 0, 0, null, false,
                                    null, null, null, null, null, null, e.getMessage(), Arrays.asList(raw)));
                        }
                    }
                }
                return new Outcome(rows, Collections.emptyList());
            } catch (Exception e) {
                throw new RuntimeException("セッション検索中にエラーが発生しました: " + e.getMessage(), e);
            }
        });
    }

    private List<LookupRow> lookupMerged(CoreProtectAPI api, LookupQuery query) {
        List<Object> blocks = query.resolveBlocks(query.blockNames);
        List<Object> excludeBlocks = query.resolveBlocks(query.excludeBlockNames);

        int need = query.offset + query.rowCount;
        List<LookupRow> merged = new ArrayList<>();
        List<LookupQuery.QueryTarget> targets = query.queryTargets();
        boolean usernameFetched = false;

        for (LookupQuery.QueryTarget target : targets) {
            merged.addAll(fetchTarget(api, query, target, blocks, excludeBlocks, need, usernameFetched));
            if (target.actionIds().contains(ActionType.USERNAME.id())) {
                usernameFetched = true;
            }
        }

        merged.sort((a, b) -> Long.compare(b.timeSeconds, a.timeSeconds));

        int from = Math.min(query.offset, merged.size());
        int to = Math.min(query.offset + query.rowCount, merged.size());
        return new ArrayList<>(merged.subList(from, to));
    }

    private List<LookupRow> fetchTarget(CoreProtectAPI api, LookupQuery query, LookupQuery.QueryTarget target,
                                        List<Object> blocks, List<Object> excludeBlocks, int need, boolean usernameFetched) {
        List<Integer> actionIds = target.actionIds();
        List<Integer> blockIds = new ArrayList<>();
        boolean wantContainer = false;
        boolean wantInventory = false;
        boolean wantChat = false;
        boolean wantCommand = false;
        boolean wantSession = false;
        boolean wantUsername = false;

        for (Integer id : actionIds) {
            if (id == null) continue;
            if (id >= 0 && id <= 3) {
                blockIds.add(id);
            } else if (LookupQuery.CONTAINER_ACTION_IDS.contains(id)) {
                wantContainer = true;
            } else if (LookupQuery.INVENTORY_ACTION_IDS.contains(id)) {
                wantInventory = true;
            } else if (id == ActionType.CHAT.id()) {
                wantChat = true;
            } else if (id == ActionType.COMMAND.id()) {
                wantCommand = true;
            } else if (id == ActionType.SESSION.id()) {
                wantSession = true;
            } else if (id == ActionType.USERNAME.id()) {
                wantUsername = true;
            }
        }

        List<LookupRow> rows = new ArrayList<>();

        if (!blockIds.isEmpty()) {
            rows.addAll(fetchBlockRows(api, query, target, blocks, excludeBlocks, blockIds, need));
        }

        if (wantContainer) {
            rows.addAll(fetchContainerRows(api, query, target, blocks, excludeBlocks, need));
        }

        if (wantInventory) {
            rows.addAll(fetchInventoryRows(api, query, target, blocks, excludeBlocks, need));
        }

        if (wantChat) {
            rows.addAll(fetchChatRows(api, query, target, need));
        }

        if (wantCommand) {
            rows.addAll(fetchCommandRows(api, query, target, need));
        }

        if (wantSession) {
            rows.addAll(fetchSessionRows(api, query, target, need));
        }

        if (wantUsername && !usernameFetched) {
            rows.addAll(fetchUsernameRows(api, query, target, need));
        }

        return rows;
    }

    private List<LookupRow> fetchBlockRows(CoreProtectAPI api, LookupQuery query, LookupQuery.QueryTarget target,
                                           List<Object> blocks, List<Object> excludeBlocks, List<Integer> blockIds, int need) {
        List<String[]> rawRows = api.performPartialLookup(
                query.timeSeconds, new ArrayList<>(query.users), new ArrayList<>(query.excludeUsers),
                new ArrayList<>(blocks), new ArrayList<>(excludeBlocks),
                new ArrayList<>(blockIds), target.radius(), target.location(), 0, need);

        if (rawRows == null) {
            return Collections.emptyList();
        }

        List<LookupRow> rows = new ArrayList<>();
        for (String[] raw : rawRows) {
            try {
                CoreProtectAPI.ParseResult parsed = api.parseResult(raw);
                rows.add(new LookupRow(
                        parsed.getTime(),
                        parsed.getPlayer(),
                        parsed.getActionId(),
                        parsed.getActionString(),
                        parsed.worldName(),
                        parsed.getX(),
                        parsed.getY(),
                        parsed.getZ(),
                        parsed.getType() != null ? parsed.getType().name() : null,
                        parsed.isRolledBack(),
                        null,
                        null,
                        null,
                        null,
                        parsed.getBlockData() != null ? parsed.getBlockData().getAsString() : null,
                        null,
                        null,
                        Arrays.asList(raw)
                ));
            } catch (Exception e) {
                rows.add(new LookupRow(0, null, Integer.MIN_VALUE, null, null, 0, 0, 0, null, false,
                        null, null, null, null, null, null, e.getMessage(), Arrays.asList(raw)));
            }
        }
        return rows;
    }

    private LookupOptions buildOptions(String user, LookupQuery query, LookupQuery.QueryTarget target, int need,
                                       List<Object> blocks, List<Object> excludeBlocks) {
        LookupOptions.Builder builder = LookupOptions.builder()
                .time(query.timeSeconds)
                .limit(0, need);

        if (user != null && !user.isEmpty() && !user.equals("#global")) {
            builder.user(user);
        }
        if (!query.excludeUsers.isEmpty()) {
            builder.excludeUsers(query.excludeUsers);
        }
        if (target.location() != null) {
            builder.radius(target.location(), target.radius());
        }

        if (blocks != null && !blocks.isEmpty()) {
            List<Material> materials = new ArrayList<>();
            for (Object obj : blocks) {
                if (obj instanceof Material) {
                    materials.add((Material) obj);
                }
            }
            if (!materials.isEmpty()) {
                builder.includeMaterials(materials);
            }
        }
        if (excludeBlocks != null && !excludeBlocks.isEmpty()) {
            List<Material> materials = new ArrayList<>();
            for (Object obj : excludeBlocks) {
                if (obj instanceof Material) {
                    materials.add((Material) obj);
                }
            }
            if (!materials.isEmpty()) {
                builder.excludeMaterials(materials);
            }
        }

        return builder.build();
    }

    private List<LookupRow> fetchContainerRows(CoreProtectAPI api, LookupQuery query, LookupQuery.QueryTarget target,
                                               List<Object> blocks, List<Object> excludeBlocks, int need) {
        List<String> users = query.users.isEmpty() ? Collections.singletonList("#global") : query.users;
        List<LookupRow> rows = new ArrayList<>();

        for (String user : users) {
            if (rows.size() >= need) break;
            LookupOptions options = buildOptions(user, query, target, need, blocks, excludeBlocks);
            List<ContainerResult> results = api.containerLookup(options);
            if (results == null) continue;

            for (ContainerResult result : results) {
                if (rows.size() >= need) break;
                if (isExcluded(result.getPlayer(), query.excludeUsers)) continue;

                Material material = result.getType();
                String direction = result.getActionId() == 1 ? "in" : "out";
                rows.add(new LookupRow(
                        result.getTimestamp() / 1000L,
                        result.getPlayer(),
                        ActionType.CONTAINER.id(),
                        result.getActionString(),
                        result.worldName(),
                        result.getX(),
                        result.getY(),
                        result.getZ(),
                        material != null ? material.name() : "AIR",
                        result.isRolledBack(),
                        result.getAmount(),
                        direction,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Collections.emptyList()
                ));
            }
        }
        return rows;
    }

    private List<LookupRow> fetchInventoryRows(CoreProtectAPI api, LookupQuery query, LookupQuery.QueryTarget target,
                                               List<Object> blocks, List<Object> excludeBlocks, int need) {
        List<String> users = query.users.isEmpty() ? Collections.singletonList("#global") : query.users;
        List<LookupRow> rows = new ArrayList<>();

        for (String user : users) {
            if (rows.size() >= need) break;
            LookupOptions options = buildOptions(user, query, target, need, blocks, excludeBlocks);
            List<InventoryResult> results = api.inventoryLookup(options);
            if (results == null) continue;

            for (InventoryResult result : results) {
                if (rows.size() >= need) break;
                if (isExcluded(result.getPlayer(), query.excludeUsers)) continue;

                Material material = result.getType();
                rows.add(new LookupRow(
                        result.getTimestamp() / 1000L,
                        result.getPlayer(),
                        ActionType.INVENTORY.id(),
                        result.getActionString(),
                        result.worldName(),
                        result.getX(),
                        result.getY(),
                        result.getZ(),
                        material != null ? material.name() : "AIR",
                        result.isRolledBack(),
                        result.getAmount(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Collections.emptyList()
                ));
            }
        }
        return rows;
    }

    private List<LookupRow> fetchChatRows(CoreProtectAPI api, LookupQuery query, LookupQuery.QueryTarget target, int need) {
        List<String> users = query.users.isEmpty() ? Collections.singletonList("#global") : query.users;
        List<LookupRow> rows = new ArrayList<>();

        for (String user : users) {
            if (rows.size() >= need) break;
            LookupOptions options = buildOptions(user, query, target, need, null, null);
            List<MessageResult> results = api.chatLookup(options);
            if (results == null) continue;

            for (MessageResult result : results) {
                if (rows.size() >= need) break;
                if (isExcluded(result.getPlayer(), query.excludeUsers)) continue;

                rows.add(new LookupRow(
                        result.getTimestamp() / 1000L,
                        result.getPlayer(),
                        ActionType.CHAT.id(),
                        ActionType.CHAT.displayName(),
                        result.worldName(),
                        result.getX(),
                        result.getY(),
                        result.getZ(),
                        null,
                        false,
                        null,
                        null,
                        result.getMessage(),
                        null,
                        null,
                        null,
                        null,
                        Collections.emptyList()
                ));
            }
        }
        return rows;
    }

    private List<LookupRow> fetchCommandRows(CoreProtectAPI api, LookupQuery query, LookupQuery.QueryTarget target, int need) {
        List<String> users = query.users.isEmpty() ? Collections.singletonList("#global") : query.users;
        List<LookupRow> rows = new ArrayList<>();

        for (String user : users) {
            if (rows.size() >= need) break;
            LookupOptions options = buildOptions(user, query, target, need, null, null);
            List<MessageResult> results = api.commandLookup(options);
            if (results == null) continue;

            for (MessageResult result : results) {
                if (rows.size() >= need) break;
                if (isExcluded(result.getPlayer(), query.excludeUsers)) continue;

                rows.add(new LookupRow(
                        result.getTimestamp() / 1000L,
                        result.getPlayer(),
                        ActionType.COMMAND.id(),
                        ActionType.COMMAND.displayName(),
                        result.worldName(),
                        result.getX(),
                        result.getY(),
                        result.getZ(),
                        null,
                        false,
                        null,
                        null,
                        result.getMessage(),
                        null,
                        null,
                        null,
                        null,
                        Collections.emptyList()
                ));
            }
        }
        return rows;
    }

    private List<LookupRow> fetchSessionRows(CoreProtectAPI api, LookupQuery query, LookupQuery.QueryTarget target, int need) {
        List<String> users = query.users.isEmpty() ? Collections.singletonList("#global") : query.users;
        List<LookupRow> rows = new ArrayList<>();

        for (String user : users) {
            if (rows.size() >= need) break;
            LookupOptions options = buildOptions(user, query, target, need, null, null);
            List<SessionResult> results = api.sessionLookup(options);
            if (results == null) continue;

            for (SessionResult result : results) {
                if (rows.size() >= need) break;
                if (isExcluded(result.getPlayer(), query.excludeUsers)) continue;

                rows.add(new LookupRow(
                        result.getTimestamp() / 1000L,
                        result.getPlayer(),
                        ActionType.SESSION.id(),
                        result.getActionString(),
                        result.worldName(),
                        result.getX(),
                        result.getY(),
                        result.getZ(),
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Collections.emptyList()
                ));
            }
        }
        return rows;
    }

    private List<LookupRow> fetchUsernameRows(CoreProtectAPI api, LookupQuery query, LookupQuery.QueryTarget target, int need) {
        List<String> users = query.users.isEmpty() ? Collections.singletonList("#global") : query.users;
        List<LookupRow> rows = new ArrayList<>();

        for (String user : users) {
            if (rows.size() >= need) break;
            LookupOptions options = buildOptions(user, query, target, need, null, null);
            List<UsernameResult> results = api.usernameLookup(options);
            if (results == null) continue;

            for (UsernameResult result : results) {
                if (rows.size() >= need) break;
                if (isExcluded(result.getPlayer(), query.excludeUsers)) continue;

                rows.add(new LookupRow(
                        result.getTimestamp() / 1000L,
                        result.getPlayer(),
                        ActionType.USERNAME.id(),
                        ActionType.USERNAME.displayName(),
                        null,
                        0,
                        0,
                        0,
                        null,
                        false,
                        null,
                        null,
                        result.getPlayer() + " -> " + result.getUuid(),
                        null,
                        null,
                        null,
                        null,
                        Collections.emptyList()
                ));
            }
        }
        return rows;
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
