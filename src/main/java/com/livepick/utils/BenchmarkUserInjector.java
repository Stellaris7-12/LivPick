package com.livepick.utils;

import cn.hutool.core.util.StrUtil;
import com.livepick.dto.UserDTO;
import com.livepick.service.benchmark.BenchmarkRuntimeConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

@Component
@RequiredArgsConstructor
public class BenchmarkUserInjector {

    public static final String HEADER_BENCHMARK_USER_ID = "X-Benchmark-User-Id";

    private final BenchmarkRuntimeConfigService runtimeConfigService;

    public boolean injectIfPresent(HttpServletRequest request) {
        if (!runtimeConfigService.isAuthBypassEnabled()) {
            return false;
        }
        if (UserHolder.getUser() != null) {
            return false;
        }
        String userIdHeader = request.getHeader(HEADER_BENCHMARK_USER_ID);
        if (StrUtil.isBlank(userIdHeader)) {
            return false;
        }
        UserDTO userDTO = new UserDTO();
        userDTO.setId(Long.valueOf(userIdHeader.trim()));
        userDTO.setNickName("benchmark-user-" + userDTO.getId());
        userDTO.setIcon("");
        UserHolder.saveUser(userDTO);
        return true;
    }
}
