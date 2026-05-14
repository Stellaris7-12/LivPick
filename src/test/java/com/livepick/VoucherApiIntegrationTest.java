package com.livepick;

import com.livepick.entity.Shop;
import com.livepick.entity.Voucher;
import com.livepick.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
@SpringBootTest
@AutoConfigureMockMvc
class VoucherApiIntegrationTest extends ApiTestSupport {

    @Test
    void shouldQueryVoucherListOfShop() throws Exception {
        Shop shop = requireAnyShop();
        Voucher voucher = createSeckillVoucherFixture("api-seckill");

        mockMvc.perform(get("/voucher/list/{shopId}", shop.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[*].title", hasItem(voucher.getTitle())))
                .andExpect(jsonPath("$.data[*].type", hasItem(1)))
                .andExpect(jsonPath("$.data[*].stock", hasItem(10)));
    }
}
