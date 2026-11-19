package org.duckdb;

public final class MonomerDuckDbTestAccess {
    private MonomerDuckDbTestAccess() {}

    public static boolean connectionLockHeld(DuckDBConnection connection) {
        return connection.connRefLock.isLocked();
    }
}
