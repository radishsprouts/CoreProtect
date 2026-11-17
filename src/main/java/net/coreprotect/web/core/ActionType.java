package net.coreprotect.web.core;

/**
 * CoreProtect の action id と日本語表示名の対応。
 * 0-3 はブロック操作・キル・クリック。
 * 4以降はカテゴリ専用 lookup（container / inventory / chat 等）。
 */
public enum ActionType {
    BLOCK_BREAK(0, "ブロック破壊"),
    BLOCK_PLACE(1, "ブロック設置"),
    CLICK(2, "クリック操作"),
    KILL(3, "キル"),
    CONTAINER(4, "コンテナ操作"),
    CHAT(6, "チャット"),
    COMMAND(7, "コマンド実行"),
    SESSION(8, "ログイン/ログアウト"),
    USERNAME(9, "ユーザー名変更"),
    INVENTORY(10, "インベントリ操作");

    private final int id;
    private final String displayName;

    ActionType(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public int id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static ActionType fromId(int id) {
        for (ActionType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return null;
    }
}
