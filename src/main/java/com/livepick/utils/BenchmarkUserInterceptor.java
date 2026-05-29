package com.livepick.utils;

import com.livepick.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class BenchmarkUserInterceptor implements HandlerInterceptor {

    private static final String BENCHMARK_USER_ID_HEADER = "X-Benchmark-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userIdHeader = request.getHeader(BENCHMARK_USER_ID_HEADER);
        if (userIdHeader == null || userIdHeader.trim().isEmpty()) {
            return true;
        }

        try {
            long userId = Long.parseLong(userIdHeader.trim());
            UserDTO userDTO = new UserDTO();
            userDTO.setId(userId);
            userDTO.setNickName("benchmark_" + userId);
            UserHolder.saveUser(userDTO);
            return true;
        } catch (NumberFormatException e) {
            response.setStatus(400);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}
