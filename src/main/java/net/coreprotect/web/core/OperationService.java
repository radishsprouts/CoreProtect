package net.coreprotect.web.core;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;

import java.util.ArrayList;
import java.util.List;

/**
 * ロールバック・リストア・パージの実行そのものを担う。
 * 専用の直列実行スレッド（JobManager）から呼び出される。
 */
public class OperationService {
    private final CoreProtect plugin;

    public OperationService(CoreProtect plugin) {
        this.plugin = plugin;
    }

    @FunctionalInterface
    private interface TargetCall {
        List<String[]> call(LookupQuery.QueryTarget target, List<Object> blocks, List<Object> excludeBlocks);
    }

    private List<String[]> runOverTargets(LookupQuery query, TargetCall call) {
        List<Object> blocks = query.resolveBlocks(query.blockNames);
        List<Object> excludeBlocks = query.resolveBlocks(query.excludeBlockNames);

        List<String[]> merged = new ArrayList<>();
        for (LookupQuery.QueryTarget target : query.queryTargets()) {
            List<String[]> rows = call.call(target, blocks, excludeBlocks);
            if (rows != null) {
                merged.addAll(rows);
            }
        }
        return merged;
    }

    public List<String[]> rollback(LookupQuery query) {
        CoreProtectAPI api = plugin.getAPI();
        if (api == null || !api.isEnabled()) {
            throw new IllegalStateException("CoreProtect API が利用できません。");
        }
        return runOverTargets(query, (target, blocks, excludeBlocks) -> api.performRollback(
                query.timeSeconds, new ArrayList<>(query.users), new ArrayList<>(query.excludeUsers),
                new ArrayList<>(blocks), new ArrayList<>(excludeBlocks),
                new ArrayList<>(target.actionIds()), target.radius(), target.location()));
    }

    public List<String[]> restore(LookupQuery query) {
        CoreProtectAPI api = plugin.getAPI();
        if (api == null || !api.isEnabled()) {
            throw new IllegalStateException("CoreProtect API が利用できません。");
        }
        return runOverTargets(query, (target, blocks, excludeBlocks) -> api.performRestore(
                query.timeSeconds, new ArrayList<>(query.users), new ArrayList<>(query.excludeUsers),
                new ArrayList<>(blocks), new ArrayList<>(excludeBlocks),
                new ArrayList<>(target.actionIds()), target.radius(), target.location()));
    }

    public void purge(int timeSeconds) {
        CoreProtectAPI api = plugin.getAPI();
        if (api == null || !api.isEnabled()) {
            throw new IllegalStateException("CoreProtect API が利用できません。");
        }
        api.performPurge(timeSeconds);
    }
}
