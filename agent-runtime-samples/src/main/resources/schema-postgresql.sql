-- Agent Runtime 表结构（PostgreSQL）——M8 自动装配启动时执行。
-- 与 DESIGN.md §7 一致，唯一的改动是全部加 IF NOT EXISTS：自动装配会在每次启动时跑，
-- 必须可重复执行（幂等 DDL）。生产更推荐 Flyway/Liquibase，这里的脚本是「零 SQL 运维」的兜底。

CREATE TABLE IF NOT EXISTS task (
    task_id        VARCHAR(64) PRIMARY KEY,
    agent_name     VARCHAR(128) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    user_id        VARCHAR(64),
    user_request   TEXT,
    current_step   INT          NOT NULL DEFAULT 0,
    context_json   JSONB,
    total_cost     NUMERIC(12,4) NOT NULL DEFAULT 0,
    total_tokens   BIGINT        NOT NULL DEFAULT 0,
    owner_id       VARCHAR(128),              -- M10：持有租约的实例
    heartbeat_at   TIMESTAMPTZ,               -- M10：最后一次心跳（决定租约是否过期）
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- M10：给升级前建的库补列（幂等）。没有 owner/heartbeat 的老行一律视为「无主」，
-- 只有明确过期才会被批量恢复当成孤儿。
ALTER TABLE task ADD COLUMN IF NOT EXISTS owner_id VARCHAR(128);
ALTER TABLE task ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_task_lease ON task(status, heartbeat_at);

CREATE TABLE IF NOT EXISTS task_event (
    id           BIGSERIAL PRIMARY KEY,
    task_id      VARCHAR(64) NOT NULL REFERENCES task(task_id),
    step_index   INT         NOT NULL,
    step_type    VARCHAR(32) NOT NULL,
    tool_name    VARCHAR(128),
    payload      JSONB,
    success      BOOLEAN,
    tokens       BIGINT,
    cost         NUMERIC(12,4),
    duration_ms  BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_event_task ON task_event(task_id, step_index);

CREATE TABLE IF NOT EXISTS idempotency_record (
    idempotency_key VARCHAR(256) PRIMARY KEY,
    task_id         VARCHAR(64) NOT NULL,
    step_index      INT         NOT NULL,
    status          VARCHAR(16) NOT NULL,
    result_payload  JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_idem_task ON idempotency_record(task_id);

CREATE TABLE IF NOT EXISTS outbox (
    id            BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(64)  NOT NULL,
    aggregate_id  VARCHAR(64)  NOT NULL,
    payload       JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(1024)
);
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox(published_at, id);

-- P0 安全：运维审计（危险操作不可抵赖的证据，不能被日志轮转掉）
CREATE TABLE IF NOT EXISTS ops_audit (
    id            BIGSERIAL PRIMARY KEY,
    at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    action        VARCHAR(32)  NOT NULL,
    task_id       VARCHAR(64),
    owner_user_id VARCHAR(64),
    principal     VARCHAR(128),
    note          VARCHAR(2048),
    allowed       BOOLEAN      NOT NULL,
    reason        VARCHAR(1024)
);
CREATE INDEX IF NOT EXISTS idx_ops_audit_at ON ops_audit(at DESC);

-- M14：跨实例共享注册表。多副本部署时用 JdbcInstanceRegistry 替掉内存版，
-- 让「owner 是否还活着」有第二个独立信号，不再退化成永远返回 false 的摆设。
CREATE TABLE IF NOT EXISTS agent_instance (
    instance_id    VARCHAR(128) PRIMARY KEY,
    last_heartbeat TIMESTAMPTZ NOT NULL,
    registered_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_instance_heartbeat ON agent_instance(last_heartbeat);
