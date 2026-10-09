package com.metaverse.coin.strategy.htx.service;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Process-lifetime ownership shared with Python; covers every HTX trend account. */
@Component
public class HtxTrendWriterLock implements DisposableBean {
    static final String LOCK_NAME = "coinstrategy:htx:trend:writer:v1";
    private final DataSource dataSource;
    private Connection connection;
    private boolean lost;

    public HtxTrendWriterLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public synchronized void assertOwned() {
        if (lost) {
            throw new IllegalStateException("HTX trend writer ownership lost; restart required");
        }
        try {
            if (connection == null) {
                connection = dataSource.getConnection();
                if (!check("SELECT GET_LOCK(?,0)")) {
                    close();
                    throw new IllegalStateException("another HTX trend writer owns execution");
                }
            }
            if (!check("SELECT IS_USED_LOCK(?)=CONNECTION_ID()")) {
                close();
                throw new IllegalStateException("HTX trend writer ownership lost");
            }
        } catch (SQLException error) {
            close();
            throw new IllegalStateException("cannot verify HTX trend writer ownership", error);
        }
    }

    private boolean check(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, LOCK_NAME);
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    @Override
    public void destroy() {
        close();
    }

    public synchronized void close() {
        lost = true;
        if (connection == null) {
            return;
        }
        try {
            // A pooled Connection.close() need not close the MySQL session.
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, LOCK_NAME);
                statement.execute();
            }
        } catch (SQLException error) {
            // On a broken session MySQL releases its advisory locks automatically.
        } finally {
            try {
                connection.close();
            } catch (SQLException error) {
                // Ownership is already permanently revoked in this process.
            }
            connection = null;
        }
    }
}
