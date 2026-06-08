package com.livepick.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaPatchRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureVoucherOrderReconciliationColumn();
        ensureOutboxTable();
        ensureReconcileLogTable();
        ensureRollbackFailureLogTable();
    }

    private void ensureVoucherOrderReconciliationColumn() {
        if (!columnExists("tb_voucher_order", "reconciliation_status")) {
            jdbcTemplate.execute("ALTER TABLE tb_voucher_order " +
                    "ADD COLUMN reconciliation_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'reconcile status'");
            log.info("added tb_voucher_order.reconciliation_status");
        }
    }

    private void ensureOutboxTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS tb_mq_outbox_message (" +
                "id BIGINT NOT NULL PRIMARY KEY," +
                "message_id VARCHAR(64) NOT NULL," +
                "biz_type VARCHAR(64) NOT NULL," +
                "biz_key VARCHAR(64) NOT NULL," +
                "topic VARCHAR(128) NOT NULL," +
                "message_key VARCHAR(64) NOT NULL," +
                "payload TEXT NOT NULL," +
                "status VARCHAR(32) NOT NULL," +
                "retry_count INT NOT NULL DEFAULT 0," +
                "next_retry_at DATETIME NOT NULL," +
                "last_error VARCHAR(1024) NULL," +
                "created_time DATETIME NOT NULL," +
                "update_time DATETIME NOT NULL," +
                "UNIQUE KEY uk_outbox_message_id (message_id)," +
                "KEY idx_outbox_status_retry (status, next_retry_at)," +
                "KEY idx_outbox_biz_key (biz_key)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    private void ensureReconcileLogTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS tb_voucher_reconcile_log (" +
                "id BIGINT NOT NULL PRIMARY KEY," +
                "message_id VARCHAR(64) NULL," +
                "trace_id BIGINT NULL," +
                "order_id BIGINT NOT NULL," +
                "voucher_id BIGINT NOT NULL," +
                "user_id BIGINT NOT NULL," +
                "log_type VARCHAR(32) NOT NULL," +
                "source VARCHAR(64) NOT NULL," +
                "detail VARCHAR(1024) NULL," +
                "before_qty INT NULL," +
                "change_qty INT NULL," +
                "after_qty INT NULL," +
                "reconciliation_status VARCHAR(32) NOT NULL," +
                "created_time DATETIME NOT NULL," +
                "update_time DATETIME NOT NULL," +
                "KEY idx_reconcile_trace (trace_id)," +
                "KEY idx_reconcile_message (message_id)," +
                "KEY idx_reconcile_order (order_id)," +
                "KEY idx_reconcile_status (reconciliation_status)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    private void ensureRollbackFailureLogTable() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS tb_rollback_failure_log (" +
                "id BIGINT NOT NULL PRIMARY KEY," +
                "trace_id BIGINT NULL," +
                "order_id BIGINT NOT NULL," +
                "voucher_id BIGINT NOT NULL," +
                "user_id BIGINT NOT NULL," +
                "result_code INT NOT NULL," +
                "retry_attempts INT NOT NULL," +
                "source VARCHAR(64) NOT NULL," +
                "detail VARCHAR(1024) NULL," +
                "status VARCHAR(32) NOT NULL," +
                "created_time DATETIME NOT NULL," +
                "update_time DATETIME NOT NULL," +
                "KEY idx_rollback_status (status)," +
                "KEY idx_rollback_trace (trace_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class,
                tableName,
                columnName
        );
        return count != null && count > 0;
    }
}
