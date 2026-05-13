package com.livepick.tools;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class SeckillRedisPreheatMain {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String ORDER_KEY_PREFIX = "seckill:order:";

    public static void main(String[] args) throws Exception {
        PreheatOptions options = PreheatOptions.parse(args);

        String dbHost = readConfig("DB_HOST", "127.0.0.1");
        String dbPort = readConfig("DB_PORT", "3306");
        String dbName = readConfig("DB_NAME", "hmdp");
        String dbUsername = readConfig("DB_USERNAME", "root");
        String dbPassword = readConfig("DB_PASSWORD", "");

        String redisHost = readConfig("REDIS_HOST", "127.0.0.1");
        int redisPort = Integer.parseInt(readConfig("REDIS_PORT", "6379"));
        String redisPassword = readConfig("REDIS_PASSWORD", "");

        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%s/%s?useSSL=false&serverTimezone=UTC",
                dbHost, dbPort, dbName
        );

        List<SeckillVoucherRecord> records = loadSeckillVouchers(jdbcUrl, dbUsername, dbPassword, options);
        if (records.isEmpty()) {
            System.out.println("No seckill vouchers found for preheat.");
            return;
        }

        RedisURI.Builder redisUriBuilder = RedisURI.Builder.redis(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            redisUriBuilder.withPassword(redisPassword.toCharArray());
        }

        RedisClient redisClient = RedisClient.create(redisUriBuilder.build());
        try (StatefulRedisConnection<String, String> redisConnection = redisClient.connect()) {
            RedisCommands<String, String> redis = redisConnection.sync();

            int total = 0;
            for (SeckillVoucherRecord record : records) {
                String stockKey = STOCK_KEY_PREFIX + record.getVoucherId();
                String orderKey = ORDER_KEY_PREFIX + record.getVoucherId();

                redis.set(stockKey, String.valueOf(record.getStock()));
                redis.del(orderKey);

                total++;
                System.out.printf(
                        "Preheated voucherId=%d, stock=%d, beginTime=%s, endTime=%s%n",
                        record.getVoucherId(),
                        record.getStock(),
                        record.getBeginTime(),
                        record.getEndTime()
                );
            }

            System.out.printf("Preheat completed. Total vouchers: %d%n", total);
        } finally {
            redisClient.shutdown();
        }
    }

    private static List<SeckillVoucherRecord> loadSeckillVouchers(
            String jdbcUrl,
            String username,
            String password,
            PreheatOptions options
    ) throws Exception {
        List<SeckillVoucherRecord> result = new ArrayList<>();

        StringBuilder sql = new StringBuilder(
                "SELECT voucher_id, stock, begin_time, end_time FROM tb_seckill_voucher"
        );
        List<Long> voucherIds = options.getVoucherIds();
        if (!voucherIds.isEmpty()) {
            String placeholders = voucherIds.stream()
                    .map(id -> "?")
                    .collect(Collectors.joining(","));
            sql.append(" WHERE voucher_id IN (").append(placeholders).append(")");
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {

            for (int i = 0; i < voucherIds.size(); i++) {
                statement.setLong(i + 1, voucherIds.get(i));
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                LocalDateTime now = LocalDateTime.now();
                while (resultSet.next()) {
                    LocalDateTime beginTime = toLocalDateTime(resultSet.getTimestamp("begin_time"));
                    LocalDateTime endTime = toLocalDateTime(resultSet.getTimestamp("end_time"));

                    if (options.isOnlyActive()) {
                        if (beginTime == null || endTime == null || now.isBefore(beginTime) || now.isAfter(endTime)) {
                            continue;
                        }
                    }

                    result.add(new SeckillVoucherRecord(
                            resultSet.getLong("voucher_id"),
                            resultSet.getInt("stock"),
                            beginTime,
                            endTime
                    ));
                }
            }
        }

        return result;
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static String readConfig(String key, String defaultValue) {
        String systemProperty = System.getProperty(key);
        if (systemProperty != null && !systemProperty.trim().isEmpty()) {
            return systemProperty.trim();
        }
        String envValue = System.getenv(key);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue.trim();
        }
        return defaultValue;
    }

    private static final class PreheatOptions {
        private final boolean onlyActive;
        private final List<Long> voucherIds;

        private PreheatOptions(boolean onlyActive, List<Long> voucherIds) {
            this.onlyActive = onlyActive;
            this.voucherIds = voucherIds;
        }

        public boolean isOnlyActive() {
            return onlyActive;
        }

        public List<Long> getVoucherIds() {
            return voucherIds;
        }

        static PreheatOptions parse(String[] args) {
            boolean onlyActive = false;
            List<Long> voucherIds = Collections.emptyList();

            for (String arg : args) {
                if ("--only-active".equals(arg)) {
                    onlyActive = true;
                    continue;
                }
                if (arg != null && arg.startsWith("--voucher-ids=")) {
                    String value = arg.substring("--voucher-ids=".length());
                    if (!value.trim().isEmpty()) {
                        voucherIds = Arrays.stream(value.split(","))
                                .map(String::trim)
                                .filter(item -> !item.isEmpty())
                                .map(Long::valueOf)
                                .collect(Collectors.toList());
                    }
                }
            }

            return new PreheatOptions(onlyActive, voucherIds);
        }
    }

    private static final class SeckillVoucherRecord {
        private final long voucherId;
        private final int stock;
        private final LocalDateTime beginTime;
        private final LocalDateTime endTime;

        private SeckillVoucherRecord(long voucherId, int stock, LocalDateTime beginTime, LocalDateTime endTime) {
            this.voucherId = voucherId;
            this.stock = stock;
            this.beginTime = beginTime;
            this.endTime = endTime;
        }

        public long getVoucherId() {
            return voucherId;
        }

        public int getStock() {
            return stock;
        }

        public LocalDateTime getBeginTime() {
            return beginTime;
        }

        public LocalDateTime getEndTime() {
            return endTime;
        }
    }
}
