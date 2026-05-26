package com.livepick;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.livepick.dto.UserDTO;
import com.livepick.entity.User;
import com.livepick.service.IUserService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.livepick.utils.RedisConstants.LOGIN_USER_KEY;
import static com.livepick.utils.RedisConstants.LOGIN_USER_TTL;
import static com.livepick.utils.SystemConstants.USER_NICK_NAME_PREFIX;
import static java.util.concurrent.TimeUnit.MINUTES;

@SpringBootTest
@Disabled("Requires Redis to write benchmark login tokens")
class SeckillBenchmarkPreparationTest {

    private static final int BENCHMARK_USER_COUNT = 300;
    private static final Path TOKEN_FILE = Paths.get("target", "benchmark", "tokens.csv");

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void prepareUsersAndTokens() throws IOException {
        List<User> users = ensureBenchmarkUsers(BENCHMARK_USER_COUNT);
        exportTokens(users);
    }

    private List<User> ensureBenchmarkUsers(int expectedCount) {
        List<String> phones = new ArrayList<>(expectedCount);
        for (int i = 1; i <= expectedCount; i++) {
            phones.add(buildPhone(i));
        }

        Map<String, User> existingUsers = userService.lambdaQuery()
                .in(User::getPhone, phones)
                .list()
                .stream()
                .collect(Collectors.toMap(User::getPhone, user -> user));

        List<User> createdUsers = new ArrayList<>();
        for (int i = 0; i < phones.size(); i++) {
            String phone = phones.get(i);
            if (existingUsers.containsKey(phone)) {
                continue;
            }
            User user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX + "benchmark_" + String.format("%03d", i + 1));
            createdUsers.add(user);
        }

        if (!createdUsers.isEmpty()) {
            userService.saveBatch(createdUsers);
        }

        return userService.lambdaQuery()
                .in(User::getPhone, phones)
                .orderByAsc(User::getId)
                .list();
    }

    private void exportTokens(List<User> users) throws IOException {
        Files.createDirectories(TOKEN_FILE.getParent());
        List<String> lines = new ArrayList<>(users.size() + 1);
        lines.add("userId,phone,token");

        for (User user : users) {
            String token = UUID.randomUUID().toString(true);
            saveToken(user, token);
            lines.add(user.getId() + "," + user.getPhone() + "," + token);
        }

        Files.write(TOKEN_FILE, lines, StandardCharsets.UTF_8);
        System.out.println("benchmark tokens exported to " + TOKEN_FILE.toAbsolutePath());
    }

    private void saveToken(User user, String token) {
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())
        );

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, MINUTES);
    }

    private String buildPhone(int sequence) {
        return String.format("188%08d", sequence);
    }
}
