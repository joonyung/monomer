package org.duckdb;

import java.nio.ByteBuffer;
import java.sql.SQLException;

/** Pinned JDBC 1.5.4.0 holds connRefLock during prepare, so Statement.cancel cannot interrupt it. */
public final class MonomerDuckDbInterrupt implements AutoCloseable {
    private final DuckDBConnection connection;
    private ByteBuffer connectionReference;

    public MonomerDuckDbInterrupt(DuckDBConnection connection) throws SQLException {
        connection.checkOpen();
        this.connection = connection;
        this.connectionReference = connection.connRef;
    }

    public synchronized void interrupt() {
        if (connectionReference != null) {
            DuckDBNative.duckdb_jdbc_interrupt(connectionReference);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connectionReference = null;
        connection.close();
    }
}
