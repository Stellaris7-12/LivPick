package com.livepick.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    private final boolean skipLoginCheck;

    public LoginInterceptor() {
        this(false);
    }

    public LoginInterceptor(boolean skipLoginCheck) {
        this.skipLoginCheck = skipLoginCheck;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (skipLoginCheck) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
