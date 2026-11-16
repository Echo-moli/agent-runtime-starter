package com.agentruntime.samples.db;

import com.agentruntime.persistence.JdbcIdempotencyStore;
import com.agentruntime.persistence.JdbcTaskRepository;
import com.agentruntime.persistence.Schema;
import com.agentruntime.persistence.TaskRepository;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 演示用的数据库支撑：<b>默认 PostgreSQL</b>，H2 只作离线兜底（M11）。
 *
 * <h2>为什么原来是 H2，现在改成 PostgreSQL</h2>
 * 之前用 H2 的 {@code MODE=PostgreSQL} 跑演示，理由是「不装数据库也能验证同一套 SQL」。
 * 代价是它只兼容语法、不兼容语义：{@code JSONB}、{@code BIGSERIAL}、
 * {@code INSERT ... SELECT ... WHERE EXISTS}（M10.2 的所有权校验）、
 * {@code NUMERIC} 的舍入行为，在 H2 上都不会按生产的行为执行。
 * 一个「生产用 PG、演示用 H2」的项目，演示通过并不等于生产通过——
 * 所以默认方言改成 PostgreSQL，DDL 主脚本也换成 {@code schema-postgresql.sql}。
 *
 * <h2>H2 为什么还留着</h2>
 * 只有两个演示（M2 崩溃续跑、M3 在途未知）需要「启动一个进程、杀进程、再开一个进程」，
 * 它们要求本机有一个可写的数据库。没装 PG 时用 {@code -Dagent.db=h2} 仍能跑完这两条链路，
 * 但这属于<b>离线兜底</b>，不是默认路径，也不再是 DDL 的主脚本。
 *
 * <h2>连接怎么配</h2>
 * <pre>
 *   -Dagent.db=pg                       # 默认；h2 = 离线兜底
 *   -Dagent.db.url=jdbc:postgresql://localhost:5432/agent_runtime
 *   -Dagent.db.user=postgres
 *   -Dagent.db.password=postgres
 * </pre>
 * 同名环境变量 {@code AGENT_DB_URL} / {@code AGENT_DB_USER} / {@code AGENT_DB_PASSWORD} 也可用
 * （系统属性优先）。仓库根目录的 {@code docker-compose.yml} 能一键起一套符合上述默认值的 PG。
 */
public final class DbSupport {

    /** 方言。 */
    public enum Dialect {
        POSTGRESQL,
        H2
    }

    public static final String DEFAULT_PG_URL = "jdbc:postgresql://localhost:5432/agent_runtime";
    public static final String DEFAULT_PG_USER = "postgres";
    public static final String DEFAULT_PG_PASSWORD = "postgres";

    /** 连不上库时统一附加的操作提示：两个动作，二选一，不留猜测空间。 */
    private static final String HINT =
            "；确认 PostgreSQL 已启动（仓库根目录 docker-compose.yml 可一键起），"
                    + "或加 -Dagent.db=h2 走离线兜底";

    /** H2 文件库目录（仅兜底路径使用）。 */
    private static final Path H2_DIR = Path.of("data").toAbsolutePath().normalize();

    private DbSupport() {
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    public static Dialect dialect() {
        String raw = setting("agent.db", "AGENT_DB");
        if (raw != null && raw.toLowerCase(Locale.ROOT).startsWith("h2")) {
            return Dialect.H2;
        }
        return Dialect.POSTGRESQL;
    }

    public static boolean isPostgres() {
        return dialect() == Dialect.POSTGRESQL;
    }

    public static String url() {
        return isPostgres() ? pgUrl() : h2Url();
    }

    public static String pgUrl() {
        String configured = setting("agent.db.url", "AGENT_DB_URL");
        return configured == null ? DEFAULT_PG_URL : configured;
    }

    public static String user() {
        if (!isPostgres()) {
            return "sa";
        }
        String configured = setting("agent.db.user", "AGENT_DB_USER");
        return configured == null ? DEFAULT_PG_USER : configured;
    }

    public static String password() {
        if (!isPostgres()) {
            return "";
        }
        String configured = setting("agent.db.password", "AGENT_DB_PASSWORD");
        return configured == null ? DEFAULT_PG_PASSWORD : configured;
    }

    /**
     * H2 兜底路径的文件库 URL。
     *
     * <p>{@code WRITE_DELAY=0}：崩溃演示用 {@code Runtime.halt()} 猝死，H2 默认 500ms 异步刷盘
     * 会把最后几笔已提交事务一起丢掉——对生产是性能取舍，对「halt 后必须能恢复」的验收是正确性问题。
     */
    public static String h2Url() {
        return "jdbc:h2:file:" + H2_DIR.toString().replace('\\', '/')
                + "/agent-runtime;MODE=PostgreSQL;WRITE_DELAY=0";
    }

    /**
     * 演示用「独立的库」：PG 用独立 schema，H2 用独立内存库。
     *
     * <p>每次调用都带随机后缀——schema 名必须是全新的，不能复用。M4 的探针任务用固定
     * taskId（如 {@code cost-probe-concurrency}）；若两次运行共用同一个 schema，
     * 第二次的 {@code save()} 会更新旧行而不是插入，出现「期望 40000 token、实际 80000」
     * 这种看起来像并发丢更新的假失败。H2 的随机内存库同理，保证同一 JVM 内重复调用也干净。
     */
    public static String memUrl(String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        if (!isPostgres()) {
            return "jdbc:h2:mem:" + name + "_" + suffix + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        }
        String schema = "rt_" + name.replaceAll("[^a-z0-9_]", "_").toLowerCase(Locale.ROOT)
                + "_" + suffix;
        createSchemaIfAbsent(schema);
        return pgUrl() + (pgUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    // ------------------------------------------------------------------
    // 打开仓储
    // ------------------------------------------------------------------

    /** 打开连接并确保表存在（DDL 全部 IF NOT EXISTS，可重复执行）。 */
    public static TaskRepository openRepository() {
        return openRepository(url());
    }

    public static TaskRepository openRepository(String jdbcUrl) {
        return new JdbcTaskRepository(openConnection(jdbcUrl));
    }

    /**
     * 打开幂等 + outbox 存储（独立连接）。
     *
     * <p>刻意不复用仓储的连接：真实系统里幂等记录与会话数据可能在不同数据源，
     * 共用同一个 {@code Connection} 会让人误以为「反正在一个事务里」，
     * 一旦换成连接池就立刻不成立。这里的原子性只承诺在 {@code idempotency_record}
     * 与 {@code outbox} 两张表之间——它们同库，可以做到；跨服务做不到，才有 outbox 这一层。
     */
    public static JdbcIdempotencyStore openIdempotencyStore() {
        return openIdempotencyStore(url());
    }

    public static JdbcIdempotencyStore openIdempotencyStore(String jdbcUrl) {
        return new JdbcIdempotencyStore(openConnection(jdbcUrl));
    }

    /** 给 Spring 演示用的数据源（M8/M9 自己声明 DataSource bean）。 */
    public static DataSource dataSource() {
        if (!isPostgres()) {
            org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
            h2.setURL(h2Url());
            h2.setUser("sa");
            return h2;
        }
        org.postgresql.ds.PGSimpleDataSource pg = new org.postgresql.ds.PGSimpleDataSource();
        pg.setUrl(pgUrl());
        pg.setUser(user());
        pg.setPassword(password());
        return pg;
    }

    /**
     * 每次运行都要干净的库（M8/M9 的端到端演示）。
     *
     * <p>PG 上用「独立 schema + 随机后缀」而不是 DROP：并行跑两个 demo 时不会互相清理，
     * 也不会碰到别人库里的数据。schema 用完留在库里，名字带 {@code rt_} 前缀便于识别与清理。
     */
    public static DataSource freshDataSource(String purpose) {
        if (!isPostgres()) {
            org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
            h2.setURL("jdbc:h2:mem:agent-runtime-" + purpose + "-" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            h2.setUser("sa");
            return h2;
        }
        String schema = "rt_" + purpose + "_" + UUID.randomUUID().toString().substring(0, 8);
        createSchemaIfAbsent(schema);
        org.postgresql.ds.PGSimpleDataSource pg = new org.postgresql.ds.PGSimpleDataSource();
        pg.setUrl(pgUrl());
        pg.setUser(user());
        pg.setPassword(password());
        pg.setCurrentSchema(schema);
        return pg;
    }

    // ------------------------------------------------------------------
    // 演示夹具
    // ------------------------------------------------------------------

    /** 崩溃演示的阶段 A：把库恢复到「什么都没有」的状态。 */
    public static void resetDatabase() {
        if (!isPostgres()) {
            deleteRecursively(H2_DIR);
            return;
        }
        try (Connection connection = DriverManager.getConnection(pgUrl(), user(), password());
             Statement statement = connection.createStatement()) {
            // 顺序与依赖相反：task_event 有指向 task 的外键
            statement.execute("DROP TABLE IF EXISTS task_event CASCADE");
            statement.execute("DROP TABLE IF EXISTS idempotency_record CASCADE");
            statement.execute("DROP TABLE IF EXISTS outbox CASCADE");
            statement.execute("DROP TABLE IF EXISTS task CASCADE");
            System.out.println("[db] PostgreSQL: 已清空演示表（" + pgUrl() + "）");
        } catch (SQLException e) {
            throw new IllegalStateException("清空 PG 演示表失败（" + pgUrl() + "）" + HINT
                    + "（原始错误：" + e.getMessage() + "）", e);
        }
    }

    /**
     * 崩溃演示的阶段 B：清掉猝死进程留下的锁。
     *
     * <p>H2 会留下 {@code .lock.db}；PostgreSQL 的锁随连接断开由服务端释放，无需处理。
     */
    public static void clearStaleLocks() {
        if (isPostgres()) {
            return;
        }
        try (Stream<Path> files = Files.list(H2_DIR)) {
            files.filter(p -> p.getFileName().toString().endsWith(".lock.db"))
                    .forEach(DbSupport::deleteQuietly);
        } catch (IOException ignored) {
            // 目录不存在说明没有历史库，直接继续
        }
    }

    /** 打印当前连接目标（每个 demo 开头一行，排查「连错了库」最快的证据）。 */
    public static String describe() {
        return isPostgres()
                ? "PostgreSQL " + pgUrl() + " (user=" + user() + ")"
                : "H2(离线兜底) " + h2Url();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Connection openConnection(String jdbcUrl) {
        try {
            Connection connection = DriverManager.getConnection(jdbcUrl, user(), password());
            Schema.runScript(connection, readResource(isPostgres()
                    ? "/schema-postgresql.sql" : "/schema-h2.sql"));
            return connection;
        } catch (SQLException e) {
            throw new IllegalStateException("无法连接数据库: " + jdbcUrl
                    + "（" + e.getMessage() + "）" + HINT, e);
        }
    }

    private static void createSchemaIfAbsent(String schema) {
        try (Connection connection = DriverManager.getConnection(pgUrl(), user(), password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException("创建 PG schema 失败: " + schema + "（" + e.getMessage() + "）"
                    + HINT, e);
        }
    }

    private static String setting(String sysProperty, String envVar) {
        String value = System.getProperty(sysProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(envVar);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String readResource(String path) {
        try (InputStream in = DbSupport.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到建表脚本: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取建表脚本失败: " + path, e);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(DbSupport::deleteQuietly);
        } catch (IOException e) {
            throw new IllegalStateException("清理数据库目录失败: " + root, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败时让数据库自己报错更直观
        }
    }
}
