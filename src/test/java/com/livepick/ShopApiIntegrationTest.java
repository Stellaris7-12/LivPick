package com.livepick;

import com.livepick.entity.Shop;
import com.livepick.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
@SpringBootTest(properties = {
        "livpick.benchmark.enabled=true",
        "livpick.benchmark.skip-login-check=true"
})
@AutoConfigureMockMvc
class ShopApiIntegrationTest extends ApiTestSupport {

    @Test
    void shouldQueryExistingShopById() throws Exception {
        Shop shop = requireAnyShop();
        registerCleanupKey("cache:shop:" + shop.getId());

        mockMvc.perform(get("/shop/{id}", shop.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(shop.getId()))
                .andExpect(jsonPath("$.data.name").isNotEmpty());
    }

    @Test
    void shouldFailWhenShopDoesNotExist() throws Exception {
        mockMvc.perform(get("/shop/{id}", Long.MAX_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").isNotEmpty());
    }

    @Test
    void shouldSwitchCachePenetrationModeThroughBenchmarkConfig() throws Exception {
        Shop shop = requireAnyShop();
        registerCleanupKey("cache:shop:" + shop.getId());

        mockMvc.perform(post("/benchmark/admin/config/cache-penetration")
                        .contentType("application/json")
                        .content("{\"mode\":\"OFF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cachePenetrationMode").value("OFF"));

        mockMvc.perform(get("/shop/{id}", shop.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/benchmark/admin/config/cache-penetration")
                        .contentType("application/json")
                        .content("{\"mode\":\"BLOOM_NULL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cachePenetrationMode").value("BLOOM_NULL"));
    }
}
