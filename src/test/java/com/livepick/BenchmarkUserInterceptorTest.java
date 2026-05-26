package com.livepick;

import com.livepick.utils.BenchmarkUserInterceptor;
import com.livepick.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkUserInterceptorTest {

    private final BenchmarkUserInterceptor interceptor = new BenchmarkUserInterceptor();

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void shouldSaveAndClearBenchmarkUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Benchmark-User-Id", "12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(12345L, UserHolder.getUser().getId());

        interceptor.afterCompletion(request, response, new Object(), null);

        assertNull(UserHolder.getUser());
    }
}
