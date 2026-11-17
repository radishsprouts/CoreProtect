package net.coreprotect.web.core;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * ログを走査し、プレイヤー別/アクション別/ブロック別/時間帯別に件数を集計するサービス。
 */
public class ActivitySummaryService {
    private static final int PAGE_SIZE = 1000;
    private static final DateTimeFormatter HOUR_BUCKET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");

    private final CoreProtect plugin;

    public ActivitySummaryService(CoreProtect plugin) {
        this.plugin = plugin;
    }

    public static final class Count {
        private final String key;
        private final long count;

        public Count(String key, long count) {
            this.key = key;
            this.count = count;
        }

        public String key() { return key; }
        public long count() { return count; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Count that = (Count) o;
            return count == that.count && Objects.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, count);
        }
    }

    public static final class Summary {
        private final int scannedRows;
        private final boolean truncated;
        private final List<Count> byPlayer;
        private final List<Count> byAction;
        private final List<Count> byBlock;
        private final List<Count> byHour;
        private final List<String> warnings;

        public Summary(int scannedRows, boolean truncated, List<Count> byPlayer, List<Count> byAction,
                       List<Count> byBlock, List<Count> byHour, List<String> warnings) {
            this.scannedRows = scannedRows;
            this.truncated = truncated;
            this.byPlayer = byPlayer;
            this.byAction = byAction;
            this.byBlock = byBlock;
            this.byHour = byHour;
            this.warnings = warnings;
        }

        public int scannedRows() { return scannedRows; }
        public boolean truncated() { return truncated; }
        public List<Count> byPlayer() { return byPlayer; }
        public List<Count> byAction() { return byAction; }
        public List<Count> byBlock() { return byBlock; }
        public List<Count> byHour() { return byHour; }
        public List<String> warnings() { return warnings; }
    }

    public CompletableFuture<Summary> summarize(LookupQuery baseQuery, int maxRows, int topN) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CoreProtectAPI api = plugin.getAPI();
                if (api == null || !api.isEnabled()) {
                    throw new IllegalStateException("CoreProtect API が利用できません。");
                }
                return doSummarize(api, baseQuery, maxRows, topN);
            } catch (Exception e) {
                throw new RuntimeException("活動サマリー集計中にエラーが発生しました: " + e.getMessage(), e);
            }
        });
    }

    private Summary doSummarize(CoreProtectAPI api, LookupQuery baseQuery, int maxRows, int topN) {
        List<Object> blocks = baseQuery.resolveBlocks(baseQuery.blockNames);
        List<Object> excludeBlocks = baseQuery.resolveBlocks(baseQuery.excludeBlockNames);

        List<String> warnings = new ArrayList<>(baseQuery.warnings);

        Map<String, Long> byPlayer = new LinkedHashMap<>();
        Map<String, Long> byAction = new LinkedHashMap<>();
        Map<String, Long> byBlock = new LinkedHashMap<>();
        Map<String, Long> byHour = new LinkedHashMap<>();

        int scanned = 0;
        boolean truncated = false;
        int parseErrors = 0;

        targetLoop:
        for (LookupQuery.QueryTarget target : baseQuery.queryTargets()) {
            int offset = 0;

            while (true) {
                int remaining = maxRows - scanned;
                if (remaining <= 0) {
                    truncated = true;
                    break targetLoop;
                }

                int fetchCount = Math.min(PAGE_SIZE, remaining);

                List<String[]> rows = api.performPartialLookup(
                        baseQuery.timeSeconds, new ArrayList<>(baseQuery.users), new ArrayList<>(baseQuery.excludeUsers),
                        new ArrayList<>(blocks), new ArrayList<>(excludeBlocks),
                        new ArrayList<>(target.actionIds()), target.radius(), target.location(), offset, fetchCount);

                if (rows == null || rows.isEmpty()) {
                    break;
                }

                for (String[] row : rows) {
                    scanned++;
                    try {
                        CoreProtectAPI.ParseResult r = api.parseResult(row);
                        increment(byPlayer, r.getPlayer());
                        increment(byAction, r.getActionString());
                        if (r.getType() != null) {
                            increment(byBlock, r.getType().name());
                        }
                        increment(byHour, hourBucket(r.getTime()));
                    } catch (Exception e) {
                        parseErrors++;
                    }
                }

                if (rows.size() < fetchCount) {
                    break;
                }
                offset += fetchCount;
            }
        }

        if (parseErrors > 0) {
            warnings.add(parseErrors + " 件の行を解析できず、集計から除外しました。");
        }

        return new Summary(scanned, truncated, top(byPlayer, topN), top(byAction, topN),
                top(byBlock, topN), top(byHour, topN), warnings);
    }

    private static void increment(Map<String, Long> map, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        map.merge(key, 1L, Long::sum);
    }

    private static String hourBucket(long epochSeconds) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault())
                .format(HOUR_BUCKET);
    }

    private static List<Count> top(Map<String, Long> counts, int n) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int limit = Math.min(n, list.size());
        List<Count> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Long> e = list.get(i);
            result.add(new Count(e.getKey(), e.getValue()));
        }
        return result;
    }
}
