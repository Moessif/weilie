package com.gakj.weilie.server.admin

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 管理平台数据库（SQLite）。
 *
 * 设计取舍：
 * - 单文件 `data/admin.db`，WAL 模式，随服务端数据目录一起备份；
 * - 不引入连接池：SQLite 单进程读写，每次操作开短连接 + `busy_timeout` 已足够，
 *   避免多一层依赖和连接泄漏风险；
 * - schema 版本记在 `PRAGMA user_version` 里，启动时按版本号顺序补迁移。
 */
class Db(dataDir: Path) {
    private val log = LoggerFactory.getLogger("weilie.admin.db")
    val file: Path = dataDir.resolve("admin.db")
    // SQLite 同一时刻只允许一个写者，且多连接并发读在同一 WAL 文件上仍可能遇到 SQLITE_BUSY。
    // 管理站并发量很小（单机 2C4G），这里用一把公平锁把访问串行化，换取稳定与可预期。
    // 注意：必须在 init 之前初始化，否则 init 里的 withConn 会用到 null。
    private val lock = ReentrantLock(true)

    init {
        Files.createDirectories(dataDir)
        Class.forName("org.sqlite.JDBC")
        withConn { c -> migrate(c) }
        log.info("管理数据库就绪：{}（schema v{}）", file.toAbsolutePath(), userVersion())
    }

    fun <T> withConn(block: (Connection) -> T): T = lock.withLock {
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout=5000")
                st.execute("PRAGMA foreign_keys=ON")
            }
            block(c)
        }
    }

    fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout=5000")
                st.execute("PRAGMA foreign_keys=ON")
            }
            c.autoCommit = false
            try {
                val r = block(c)
                c.commit()
                r
            } catch (e: Throwable) {
                runCatching { c.rollback() }
                throw e
            }
        }
    }

    fun userVersion(): Int = withConn { c ->
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun migrate(c: Connection) {
        c.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
        }
        val current = c.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        if (current >= SCHEMA_VERSION) return
        c.autoCommit = false
        try {
            for (scripts in MIGRATIONS.drop(current)) {
                for (sql in scripts) {
                    c.createStatement().use { it.execute(sql) }
                }
            }
            c.createStatement().use { it.execute("PRAGMA user_version=$SCHEMA_VERSION") }
            c.commit()
        } catch (e: Throwable) {
            runCatching { c.rollback() }
            throw IllegalStateException("管理数据库迁移失败（v$current → v$SCHEMA_VERSION）：${e.message}", e)
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2

        /** 按版本顺序排列的迁移脚本：索引 = 起始版本。 */
        private val MIGRATIONS: List<List<String>> = listOf(
            // v0 → v1：管理平台初始表
            listOf(
                """
                CREATE TABLE IF NOT EXISTS orgs (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    name          TEXT NOT NULL,
                    code          TEXT NOT NULL UNIQUE,
                    contact_name  TEXT NOT NULL DEFAULT '',
                    contact_phone TEXT NOT NULL DEFAULT '',
                    status        TEXT NOT NULL DEFAULT 'ACTIVE',
                    quota_users   INTEGER NOT NULL DEFAULT 50,
                    quota_rooms   INTEGER NOT NULL DEFAULT 5,
                    created_at    INTEGER NOT NULL,
                    updated_at    INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS org_applications (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    org_name      TEXT NOT NULL,
                    contact_name  TEXT NOT NULL DEFAULT '',
                    contact_phone TEXT NOT NULL DEFAULT '',
                    email         TEXT NOT NULL DEFAULT '',
                    reason        TEXT NOT NULL DEFAULT '',
                    status        TEXT NOT NULL DEFAULT 'PENDING',
                    review_note   TEXT NOT NULL DEFAULT '',
                    org_id        INTEGER,
                    created_at    INTEGER NOT NULL,
                    reviewed_at   INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS users (
                    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                    org_id               INTEGER,
                    username             TEXT NOT NULL UNIQUE,
                    display_name         TEXT NOT NULL DEFAULT '',
                    phone                TEXT NOT NULL DEFAULT '',
                    role                 TEXT NOT NULL DEFAULT 'USER',
                    status               TEXT NOT NULL DEFAULT 'PENDING',
                    password_hash        TEXT NOT NULL DEFAULT '',
                    device_id            TEXT NOT NULL DEFAULT '',
                    device_name          TEXT NOT NULL DEFAULT '',
                    must_change_password INTEGER NOT NULL DEFAULT 0,
                    fail_count           INTEGER NOT NULL DEFAULT 0,
                    locked_until         INTEGER NOT NULL DEFAULT 0,
                    activated_at         INTEGER NOT NULL DEFAULT 0,
                    last_login_at        INTEGER NOT NULL DEFAULT 0,
                    created_at           INTEGER NOT NULL,
                    updated_at           INTEGER NOT NULL,
                    deleted_at           INTEGER NOT NULL DEFAULT 0
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_users_org ON users(org_id, deleted_at)",
                """
                CREATE TABLE IF NOT EXISTS activation_codes (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id    INTEGER NOT NULL,
                    org_id     INTEGER,
                    code       TEXT NOT NULL UNIQUE,
                    purpose    TEXT NOT NULL DEFAULT 'ACTIVATE',
                    expires_at INTEGER NOT NULL,
                    used_at    INTEGER NOT NULL DEFAULT 0,
                    revoked_at INTEGER NOT NULL DEFAULT 0,
                    created_by INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_codes_user ON activation_codes(user_id)",
                """
                CREATE TABLE IF NOT EXISTS admin_sessions (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id      INTEGER NOT NULL,
                    token_hash   TEXT NOT NULL UNIQUE,
                    csrf_hash    TEXT NOT NULL,
                    created_at   INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL,
                    expires_at   INTEGER NOT NULL,
                    revoked_at   INTEGER NOT NULL DEFAULT 0,
                    ip           TEXT NOT NULL DEFAULT '',
                    user_agent   TEXT NOT NULL DEFAULT ''
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS client_tokens (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id      INTEGER NOT NULL,
                    token_hash   TEXT NOT NULL UNIQUE,
                    device_id    TEXT NOT NULL DEFAULT '',
                    created_at   INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL,
                    expires_at   INTEGER NOT NULL,
                    revoked_at   INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS room_meta (
                    room_id    TEXT PRIMARY KEY,
                    org_id     INTEGER,
                    created_by INTEGER,
                    room_name  TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    closed_at  INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS metrics_snapshots (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts            INTEGER NOT NULL,
                    rooms         INTEGER NOT NULL DEFAULT 0,
                    online_members INTEGER NOT NULL DEFAULT 0,
                    ws_connections INTEGER NOT NULL DEFAULT 0,
                    samples       INTEGER NOT NULL DEFAULT 0,
                    photos        INTEGER NOT NULL DEFAULT 0,
                    rate_limited  INTEGER NOT NULL DEFAULT 0,
                    errors        INTEGER NOT NULL DEFAULT 0,
                    exports       INTEGER NOT NULL DEFAULT 0
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_metrics_ts ON metrics_snapshots(ts)",
                """
                CREATE TABLE IF NOT EXISTS admin_audit (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts         INTEGER NOT NULL,
                    actor_id   INTEGER,
                    actor_name TEXT NOT NULL DEFAULT '',
                    org_id     INTEGER,
                    action     TEXT NOT NULL,
                    target     TEXT NOT NULL DEFAULT '',
                    detail     TEXT NOT NULL DEFAULT '',
                    ip         TEXT NOT NULL DEFAULT ''
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_audit_ts ON admin_audit(ts)",
                """
                CREATE TABLE IF NOT EXISTS org_daily_stats (
                    org_id  INTEGER NOT NULL,
                    day     TEXT NOT NULL,
                    samples INTEGER NOT NULL DEFAULT 0,
                    rooms   INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (org_id, day)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS notices (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    title      TEXT NOT NULL,
                    body       TEXT NOT NULL DEFAULT '',
                    level      TEXT NOT NULL DEFAULT 'INFO',
                    active     INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL
                )
                """,
            ),
        
            // v1 → v2：运行时设置（房间 TTL 等可在管理站修改）
            listOf(
                """
                CREATE TABLE IF NOT EXISTS settings (
                    key        TEXT PRIMARY KEY,
                    value      TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            ),
        )
    }
}
