package com.livepick;

import com.livepick.entity.Shop;
import com.livepick.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
@SpringBootTest
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
}
