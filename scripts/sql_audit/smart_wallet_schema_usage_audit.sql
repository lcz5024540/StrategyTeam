-- Smart wallet schema usage audit (read-only)
--
-- Scope:
--   - smart_wallet_% tables
--   - wallet_% tables
-- Excluded:
--   - single_exchange_% tables
--   - weather_% tables
--   - backup tables matching %_bak_%
--
-- Usage:
--   mysql -h49.51.250.174 -P3306 -uroot -p coin_strategy \
--     < scripts/sql_audit/smart_wallet_schema_usage_audit.sql

SET SESSION group_concat_max_len = 10000000;

SELECT
    'TABLE_OVERVIEW' AS section,
    t.table_name,
    t.table_rows,
    ROUND((t.data_length + t.index_length) / 1024 / 1024, 2) AS size_mb,
    t.table_comment
FROM information_schema.tables t
WHERE t.table_schema = DATABASE()
  AND (t.table_name LIKE 'smart_wallet\_%' OR t.table_name LIKE 'wallet\_%')
  AND t.table_name NOT LIKE '%\_bak\_%'
ORDER BY t.table_name;

SELECT
    'EMPTY_TABLE' AS section,
    t.table_name,
    t.table_comment
FROM information_schema.tables t
WHERE t.table_schema = DATABASE()
  AND (t.table_name LIKE 'smart_wallet\_%' OR t.table_name LIKE 'wallet\_%')
  AND t.table_name NOT LIKE '%\_bak\_%'
  AND t.table_rows = 0
ORDER BY t.table_name;

SELECT GROUP_CONCAT(
    CONCAT(
        'SELECT ',
        QUOTE(c.table_name), ' AS table_name, ',
        QUOTE(c.column_name), ' AS column_name, ',
        QUOTE(c.data_type), ' AS data_type, ',
        'COUNT(*) AS row_count, ',
        'SUM(`', c.column_name, '` IS NULL) AS null_count, ',
        CASE
            WHEN c.data_type IN ('char', 'varchar', 'tinytext', 'text', 'mediumtext', 'longtext')
            THEN CONCAT('SUM(`', c.column_name, '` = '''')')
            ELSE '0'
        END,
        ' AS empty_string_count, ',
        CASE
            WHEN c.data_type IN ('tinyint', 'smallint', 'mediumint', 'int', 'bigint', 'decimal', 'float', 'double')
            THEN CONCAT('SUM(`', c.column_name, '` = 0)')
            ELSE '0'
        END,
        ' AS zero_count ',
        'FROM `', c.table_name, '`'
    )
    SEPARATOR ' UNION ALL '
) INTO @column_usage_sql
FROM information_schema.columns c
JOIN information_schema.tables t
  ON t.table_schema = c.table_schema
 AND t.table_name = c.table_name
WHERE c.table_schema = DATABASE()
  AND (c.table_name LIKE 'smart_wallet\_%' OR c.table_name LIKE 'wallet\_%')
  AND c.table_name NOT LIKE '%\_bak\_%'
  AND t.table_rows > 0;

SET @column_usage_sql = CONCAT(
    'SELECT ',
    'table_name, column_name, data_type, row_count, ',
    'null_count, empty_string_count, zero_count, ',
    'ROUND(null_count / row_count * 100, 2) AS null_pct, ',
    'ROUND((null_count + empty_string_count) / row_count * 100, 2) AS blank_pct, ',
    'ROUND(zero_count / row_count * 100, 2) AS zero_pct ',
    'FROM (', @column_usage_sql, ') x ',
    'ORDER BY table_name, column_name'
);

PREPARE column_usage_stmt FROM @column_usage_sql;
EXECUTE column_usage_stmt;
DEALLOCATE PREPARE column_usage_stmt;

SELECT GROUP_CONCAT(
    CONCAT(
        'SELECT ',
        QUOTE(c.table_name), ' AS table_name, ',
        QUOTE(c.column_name), ' AS column_name, ',
        QUOTE(c.data_type), ' AS data_type, ',
        'COUNT(*) AS row_count, ',
        'SUM(`', c.column_name, '` IS NULL) AS null_count, ',
        CASE
            WHEN c.data_type IN ('char', 'varchar', 'tinytext', 'text', 'mediumtext', 'longtext')
            THEN CONCAT('SUM(`', c.column_name, '` = '''')')
            ELSE '0'
        END,
        ' AS empty_string_count, ',
        CASE
            WHEN c.data_type IN ('tinyint', 'smallint', 'mediumint', 'int', 'bigint', 'decimal', 'float', 'double')
            THEN CONCAT('SUM(`', c.column_name, '` = 0)')
            ELSE '0'
        END,
        ' AS zero_count ',
        'FROM `', c.table_name, '`'
    )
    SEPARATOR ' UNION ALL '
) INTO @suspicious_column_sql
FROM information_schema.columns c
JOIN information_schema.tables t
  ON t.table_schema = c.table_schema
 AND t.table_name = c.table_name
WHERE c.table_schema = DATABASE()
  AND (c.table_name LIKE 'smart_wallet\_%' OR c.table_name LIKE 'wallet\_%')
  AND c.table_name NOT LIKE '%\_bak\_%'
  AND t.table_rows > 0
  AND c.column_name NOT IN ('id', 'created_at', 'updated_at');

SET @suspicious_column_sql = CONCAT(
    'SELECT ',
    'table_name, column_name, data_type, row_count, ',
    'null_count, empty_string_count, zero_count, ',
    'ROUND(null_count / row_count * 100, 2) AS null_pct, ',
    'ROUND((null_count + empty_string_count) / row_count * 100, 2) AS blank_pct, ',
    'ROUND(zero_count / row_count * 100, 2) AS zero_pct, ',
    'CASE ',
    'WHEN null_count = row_count THEN ''ALL_NULL'' ',
    'WHEN null_count + empty_string_count = row_count THEN ''ALL_BLANK'' ',
    'WHEN zero_count = row_count THEN ''ALL_ZERO'' ',
    'WHEN null_count / row_count >= 0.95 THEN ''MOSTLY_NULL'' ',
    'WHEN (null_count + empty_string_count) / row_count >= 0.95 THEN ''MOSTLY_BLANK'' ',
    'WHEN zero_count / row_count >= 0.95 THEN ''MOSTLY_ZERO'' ',
    'END AS reason ',
    'FROM (', @suspicious_column_sql, ') x ',
    'WHERE null_count = row_count ',
    '   OR null_count + empty_string_count = row_count ',
    '   OR zero_count = row_count ',
    '   OR null_count / row_count >= 0.95 ',
    '   OR (null_count + empty_string_count) / row_count >= 0.95 ',
    '   OR zero_count / row_count >= 0.95 ',
    'ORDER BY table_name, reason, column_name'
);

PREPARE suspicious_column_stmt FROM @suspicious_column_sql;
EXECUTE suspicious_column_stmt;
DEALLOCATE PREPARE suspicious_column_stmt;

SELECT
    'COLUMN_NAME_REUSE' AS section,
    c.column_name,
    COUNT(DISTINCT c.table_name) AS table_count,
    GROUP_CONCAT(c.table_name ORDER BY c.table_name SEPARATOR ', ') AS tables
FROM information_schema.columns c
WHERE c.table_schema = DATABASE()
  AND (c.table_name LIKE 'smart_wallet\_%' OR c.table_name LIKE 'wallet\_%')
  AND c.table_name NOT LIKE '%\_bak\_%'
  AND c.column_name NOT IN ('id', 'created_at', 'updated_at')
GROUP BY c.column_name
HAVING COUNT(DISTINCT c.table_name) >= 3
ORDER BY table_count DESC, c.column_name;
