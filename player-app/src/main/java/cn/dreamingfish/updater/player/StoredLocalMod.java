package cn.dreamingfish.updater.player;

record StoredLocalMod(String originalPath, String storedPath, boolean playerOwned) {
    StoredLocalMod(String originalPath, String storedPath) {
        this(originalPath, storedPath, false);
    }

    StoredLocalMod ownedByPlayer() { return new StoredLocalMod(originalPath, storedPath, true); }
}
