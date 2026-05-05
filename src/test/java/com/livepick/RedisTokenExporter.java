package com.livepick;

import com.livepick.utils.RedisConstants;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Export existing login tokens from Redis to a CSV file for JMeter.
 *
 * <p>Examples:</p>
 * <pre>
 * 1. Export the first 200 tokens
 *    --limit=200 --csv=load-test-tokens.csv
 *
 * 2. Export all tokens under login:token:*
 *    --limit=0 --csv=load-test-tokens.csv
 *
 * 3. Override the Redis key pattern
 *    --pattern=login:token:* --limit=500 --csv=load-test-tokens.csv
 * </pre>
 */
public class RedisTokenExporter {

    private static final String DEFAULT_PATTERN = RedisConstants.LOGIN_USER_KEY + "*";
    private static final int DEFAULT_SCAN_COUNT = 1000;
    private static final int DEFAULT_LIMIT = 200;
    private static final String DEFAULT_CSV = "load-test-tokens.csv";

    public static void main(String[] args) throws Exception {
        ExportOptions options = ExportOptions.parse(args);
        try (ConfigurableApplicationContext context = SpringApplication.run(LivPickApplication.class)) {
            StringRedisTemplate stringRedisTemplate = context.getBean(StringRedisTemplate.class);
            new RedisTokenExporter().export(stringRedisTemplate, options);
        }
    }

    private void export(StringRedisTemplate stringRedisTemplate, ExportOptions options) throws IOException {
        List<String> tokens = scanTokens(stringRedisTemplate, options);
        if (tokens.isEmpty()) {
            throw new IllegalStateException("Redis 中未扫描到可用 token，请确认 key 前缀和 Redis 连接配置。");
        }

        Path csvPath = Paths.get(options.csvPath).toAbsolutePath().normalize();
        if (csvPath.getParent() != null) {
            Files.createDirectories(csvPath.getParent());
        }

        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
            writer.write("token");
            writer.newLine();
            for (String token : tokens) {
                writer.write(token);
                writer.newLine();
            }
        }

        System.out.printf("token 导出完成，数量=%d，csv=%s%n", tokens.size(), csvPath);
    }

    private List<String> scanTokens(StringRedisTemplate stringRedisTemplate, ExportOptions options) {
        List<String> tokens = new ArrayList<>();
        ScanOptions scanOptions = ScanOptions.scanOptions()
                .match(options.pattern)
                .count(DEFAULT_SCAN_COUNT)
                .build();

        stringRedisTemplate.execute((RedisConnection connection) -> {
            try (Cursor<byte[]> cursor = connection.scan(scanOptions)) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next(), StandardCharsets.UTF_8);
                    String token = key.substring(RedisConstants.LOGIN_USER_KEY.length());
                    if (token.isEmpty()) {
                        continue;
                    }
                    tokens.add(token);
                    if (options.limit > 0 && tokens.size() >= options.limit) {
                        break;
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫描 Redis token 失败", e);
            }
            return null;
        });

        Collections.sort(tokens);
        return tokens;
    }

    private static final class ExportOptions {
        private final String pattern;
        private final int limit;
        private final String csvPath;

        private ExportOptions(String pattern, int limit, String csvPath) {
            this.pattern = pattern;
            this.limit = limit;
            this.csvPath = csvPath;
        }

        private static ExportOptions parse(String[] args) {
            Map<String, String> argMap = new LinkedHashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException("参数格式错误：" + arg + "，应为 --key=value");
                }
                int index = arg.indexOf('=');
                argMap.put(arg.substring(2, index), arg.substring(index + 1));
            }

            String pattern = argMap.getOrDefault("pattern", DEFAULT_PATTERN);
            int limit = Integer.parseInt(argMap.getOrDefault("limit", String.valueOf(DEFAULT_LIMIT)));
            String csvPath = argMap.getOrDefault("csv", DEFAULT_CSV);

            if (!pattern.startsWith(RedisConstants.LOGIN_USER_KEY)) {
                throw new IllegalArgumentException("pattern 必须以 " + RedisConstants.LOGIN_USER_KEY + " 开头");
            }
            if (limit < 0) {
                throw new IllegalArgumentException("limit 不能小于 0；0 表示导出全部");
            }

            return new ExportOptions(pattern, limit, csvPath);
        }
    }
}
