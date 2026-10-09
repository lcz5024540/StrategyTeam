package com.metaverse.coin.strategy.htx.service;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HtxTrendWriterLockTest {
    @Test
    void retainsSessionAndFailsClosedAfterLoss() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        PreparedStatement check = mock(PreparedStatement.class);
        PreparedStatement release = mock(PreparedStatement.class);
        ResultSet acquired = mock(ResultSet.class);
        ResultSet owned = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT GET_LOCK(?,0)")).thenReturn(acquire);
        when(connection.prepareStatement("SELECT IS_USED_LOCK(?)=CONNECTION_ID()")).thenReturn(check);
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(release);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(acquired.next()).thenReturn(true);
        when(acquired.getInt(1)).thenReturn(1);
        when(check.executeQuery()).thenReturn(owned);
        when(owned.next()).thenReturn(true);
        when(owned.getInt(1)).thenReturn(1, 0);
        HtxTrendWriterLock lock = new HtxTrendWriterLock(source);
        lock.assertOwned();
        verify(connection, never()).close();
        assertThrows(IllegalStateException.class, lock::assertOwned);
        assertThrows(IllegalStateException.class, lock::assertOwned);
        verify(source, times(1)).getConnection();
        verify(release).execute();
        verify(connection).close();
    }
}
