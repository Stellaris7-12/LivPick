package com.livepick;

import cn.hutool.core.lang.UUID;
import com.livepick.utils.RedisConstants;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 压测数据生成器。
 *
 * <p>运行示例：</p>
 * <pre>
 * 1. 只生成用户
 *    --mode=users --count=10000 --phonePrefix=139 --phoneStart=10000000
 *
 * 2. 只生成 token.csv
 *    --mode=tokens --count=10000 --phonePrefix=139 --phoneStart=10000000 --csv=load-test-tokens.csv
 *
 * 3. 一次性生成用户和 token.csv
 *    --mode=all --count=10000 --phonePrefix=139 --phoneStart=10000000 --csv=load-test-tokens.csv
 * </pre>
 *
 * <p>手机号规则：phonePrefix(3位) + phoneStart开始的8位流水号，组成11位手机号。</p>
 */
public class LoadTestDataGenerator {

    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final String DEFAULT_MODE = "all";
    private static final String DEFAULT_PHONE_PREFIX = "139";
    private static final long DEFAULT_PHONE_START = 10_000_000L;
    private static final int DEFAULT_COUNT = 10_000;
    private static final String DEFAULT_CSV = "load-test-tokens.csv";

    public static void main(String[] args) throws Exception {
        GeneratorOptions options = GeneratorOptions.parse(args);
        try (ConfigurableApplicationContext context = SpringApplication.run(LivPickApplication.class)) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            StringRedisTemplate stringRedisTemplate = context.getBean(StringRedisTemplate.class);
            LoadTestDataGenerator generator = new LoadTestDataGenerator();

            if (options.shouldGenerateUsers()) {
                generator.generateUsers(jdbcTemplate, options);
            }
            if (options.shouldGenerateTokens()) {
                generator.generateTokens(jdbcTemplate, stringRedisTemplate, options);
            }
        }
    }

    private void generateUsers(JdbcTemplate jdbcTemplate, GeneratorOptions options) {
        List<UserSeed> seeds = buildSeeds(options);
        String sql = "INSERT IGNORE INTO tb_user(phone, password, nick_name, icon) VALUES (?, '', ?, '')";
        for (int i = 0; i < seeds.size(); i += DEFAULT_BATCH_SIZE) {
            List<UserSeed> batch = seeds.subList(i, Math.min(i + DEFAULT_BATCH_SIZE, seeds.size()));
            jdbcTemplate.batchUpdate(sql, batch, batch.size(), (ps, seed) -> {
                ps.setString(1, seed.getPhone());
                ps.setString(2, seed.getNickName());
            });
        }
        System.out.printf("用户生成完成，目标数量=%d，手机号范围=%s ~ %s%n",
                options.count,
                seeds.get(0).getPhone(),
                seeds.get(seeds.size() - 1).getPhone());
    }

    private void generateTokens(JdbcTemplate jdbcTemplate, StringRedisTemplate stringRedisTemplate, GeneratorOptions options)
            throws IOException {
        List<String> phones = buildSeeds(options).stream()
                .map(UserSeed::getPhone)
                .collect(Collectors.toList());
        List<Map<String, Object>> users = queryUsersByPhones(jdbcTemplate, phones);
        if (users.size() != phones.size()) {
            throw new IllegalStateException("实际查询到的用户数与目标数量不一致，目标="
                    + phones.size() + "，实际=" + users.size() + "。请先执行 users 模式。");
        }

        Path csvPath = Paths.get(options.csvPath).toAbsolutePath().normalize();
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
            writer.write("token");
            writer.newLine();

            for (Map<String, Object> user : users) {
                String token = UUID.randomUUID().toString(true);
                String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
                Map<String, String> userMap = new LinkedHashMap<>();
                userMap.put("id", Objects.toString(user.get("id"), ""));
                userMap.put("nickName", Objects.toString(user.get("nick_name"), ""));
                userMap.put("icon", Objects.toString(user.get("icon"), ""));

                stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
                stringRedisTemplate.expire(tokenKey, Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL));

                writer.write(token);
                writer.newLine();
            }
        }

        System.out.printf("token 生成完成，数量=%d，csv=%s%n", users.size(), csvPath);
    }

    private List<Map<String, Object>> queryUsersByPhones(JdbcTemplate jdbcTemplate, List<String> phones) {
        List<Map<String, Object>> result = new ArrayList<>(phones.size());
        for (int i = 0; i < phones.size(); i += DEFAULT_BATCH_SIZE) {
            List<String> batch = phones.subList(i, Math.min(i + DEFAULT_BATCH_SIZE, phones.size()));
            String placeholders = batch.stream().map(phone -> "?").collect(Collectors.joining(","));
            String sql = "SELECT id, phone, nick_name, icon FROM tb_user WHERE phone IN (" + placeholders + ")";
            result.addAll(jdbcTemplate.queryForList(sql, batch.toArray()));
        }
        Map<String, Map<String, Object>> byPhone = result.stream()
                .collect(Collectors.toMap(
                        row -> Objects.toString(row.get("phone"), ""),
                        row -> row
                ));
        return phones.stream()
                .map(byPhone::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<UserSeed> buildSeeds(GeneratorOptions options) {
        List<UserSeed> seeds = new ArrayList<>(options.count);
        for (int i = 0; i < options.count; i++) {
            long suffix = options.phoneStart + i;
            String phone = options.phonePrefix + String.format("%08d", suffix);
            String nickName = "load_user_" + String.format("%06d", i + 1);
            seeds.add(new UserSeed(phone, nickName));
        }
        return seeds;
    }

    private static final class UserSeed {
        private final String phone;
        private final String nickName;

        private UserSeed(String phone, String nickName) {
            this.phone = phone;
            this.nickName = nickName;
        }

        private String getPhone() {
            return phone;
        }

        private String getNickName() {
            return nickName;
        }
    }

    private static final class GeneratorOptions {
        private final String mode;
        private final int count;
        private final String phonePrefix;
        private final long phoneStart;
        private final String csvPath;

        private GeneratorOptions(String mode, int count, String phonePrefix, long phoneStart, String csvPath) {
            this.mode = mode;
            this.count = count;
            this.phonePrefix = phonePrefix;
            this.phoneStart = phoneStart;
            this.csvPath = csvPath;
        }

        private static GeneratorOptions parse(String[] args) {
            Map<String, String> argMap = new LinkedHashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException("参数格式错误：" + arg + "，应为 --key=value");
                }
                int index = arg.indexOf('=');
                argMap.put(arg.substring(2, index), arg.substring(index + 1));
            }

            String mode = argMap.getOrDefault("mode", DEFAULT_MODE);
            int count = Integer.parseInt(argMap.getOrDefault("count", String.valueOf(DEFAULT_COUNT)));
            String phonePrefix = argMap.getOrDefault("phonePrefix", DEFAULT_PHONE_PREFIX);
            long phoneStart = Long.parseLong(argMap.getOrDefault("phoneStart", String.valueOf(DEFAULT_PHONE_START)));
            String csvPath = argMap.getOrDefault("csv", DEFAULT_CSV);

            if (!List.of("users", "tokens", "all").contains(mode)) {
                throw new IllegalArgumentException("mode 仅支持 users / tokens / all");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("count 必须大于 0");
            }
            if (!phonePrefix.matches("\\d{3}")) {
                throw new IllegalArgumentException("phonePrefix 必须是 3 位数字");
            }
            if (phoneStart < 0 || phoneStart > 99_999_999L) {
                throw new IllegalArgumentException("phoneStart 必须在 0 ~ 99999999 之间");
            }
            if (phoneStart + count - 1 > 99_999_999L) {
                throw new IllegalArgumentException("手机号范围超出 8 位流水号上限，请减小 count 或 phoneStart");
            }

            return new GeneratorOptions(mode, count, phonePrefix, phoneStart, csvPath);
        }

        private boolean shouldGenerateUsers() {
            return "users".equals(mode) || "all".equals(mode);
        }

        private boolean shouldGenerateTokens() {
            return "tokens".equals(mode) || "all".equals(mode);
        }
    }
}
