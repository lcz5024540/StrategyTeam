"""Process-lifetime MySQL advisory lock shared with the Java trend service.

The deliberately coarse key owns ALL HTX trend accounts (including followers).
A lost lock is fatal; this process never silently reacquires ownership.
"""
LOCK_NAME = "coinstrategy:htx:trend:writer:v1"


class TrendWriterLock:
    def __init__(self, connection):
        self.connection = connection
        self.acquired = False
        self.lost = False

    def acquire(self):
        if self.acquired or self.lost:
            raise RuntimeError("writer lock cannot be reacquired")
        with self.connection.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK(%s,0) AS acquired", (LOCK_NAME,))
            if (cursor.fetchone() or {}).get("acquired") != 1:
                raise RuntimeError("another HTX trend writer owns execution")
        self.acquired = True

    def assert_owned(self):
        try:
            with self.connection.cursor() as cursor:
                cursor.execute("SELECT IS_USED_LOCK(%s)=CONNECTION_ID() AS owned", (LOCK_NAME,))
                owned = (cursor.fetchone() or {}).get("owned") == 1
            if not self.acquired or self.lost or not owned:
                raise RuntimeError("HTX trend writer ownership lost")
        except Exception:
            self.lost = True
            raise

    def close(self):
        # A TCP close is not a server acknowledgement: a replacement process
        # could reach GET_LOCK before MySQL has handled the disconnect. Release
        # synchronously on a healthy connection, then close even after errors.
        try:
            if self.acquired:
                try:
                    with self.connection.cursor() as cursor:
                        cursor.execute("SELECT RELEASE_LOCK(%s) AS released", (LOCK_NAME,))
                        cursor.fetchone()
                except Exception:
                    pass  # Disconnect still releases server ownership eventually.
        finally:
            try:
                self.connection.close()
            finally:
                self.acquired = False
                self.lost = True
