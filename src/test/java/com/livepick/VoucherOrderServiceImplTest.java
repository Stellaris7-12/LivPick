package com.livepick;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.livepick.dto.Result;
import com.livepick.dto.UserDTO;
import com.livepick.entity.SeckillVoucher;
import com.livepick.entity.VoucherOrder;
import com.livepick.service.ISeckillVoucherService;
import com.livepick.service.impl.VoucherOrderServiceImpl;
import com.livepick.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {

    @Spy
    private VoucherOrderServiceImpl voucherOrderService;

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    @Mock
    private QueryChainWrapper<VoucherOrder> queryChainWrapper;

    @Mock
    private UpdateChainWrapper<SeckillVoucher> updateChainWrapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(voucherOrderService, "seckillVoucherService", seckillVoucherService);

        UserDTO userDTO = new UserDTO();
        userDTO.setId(100L);
        UserHolder.saveUser(userDTO);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void shouldFailWhenUserIsMissing() {
        UserHolder.removeUser();

        Result result = voucherOrderService.seckillVoucher(1L);

        assertFalse(result.getSuccess());
        assertEquals("User is not logged in", result.getErrorMsg());
    }

    @Test
    void shouldFailWhenVoucherDoesNotExist() {
        when(seckillVoucherService.getById(1L)).thenReturn(null);

        Result result = voucherOrderService.seckillVoucher(1L);

        assertFalse(result.getSuccess());
        assertEquals("Voucher not found", result.getErrorMsg());
    }

    @Test
    void shouldFailWhenSeckillHasNotStarted() {
        SeckillVoucher voucher = buildVoucher(LocalDateTime.now().plusMinutes(5), LocalDateTime.now().plusHours(1));
        when(seckillVoucherService.getById(1L)).thenReturn(voucher);

        Result result = voucherOrderService.seckillVoucher(1L);

        assertFalse(result.getSuccess());
        assertEquals("Seckill has not started", result.getErrorMsg());
    }

    @Test
    void shouldFailWhenUserAlreadyOrdered() {
        SeckillVoucher voucher = buildVoucher(LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusHours(1));
        mockQueryCount(1);
        when(seckillVoucherService.getById(1L)).thenReturn(voucher);

        Result result = voucherOrderService.seckillVoucher(1L);

        assertFalse(result.getSuccess());
        assertEquals("Duplicate orders are not allowed", result.getErrorMsg());
        verify(seckillVoucherService, never()).update();
    }

    @Test
    void shouldFailWhenStockUpdateFails() {
        SeckillVoucher voucher = buildVoucher(LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusHours(1));
        mockQueryCount(0);
        mockStockUpdate(false);
        when(seckillVoucherService.getById(1L)).thenReturn(voucher);

        Result result = voucherOrderService.seckillVoucher(1L);

        assertFalse(result.getSuccess());
        assertEquals("Out of stock", result.getErrorMsg());
        verify(voucherOrderService, never()).save(any(VoucherOrder.class));
    }

    @Test
    void shouldCreateOrderWhenChecksPass() {
        SeckillVoucher voucher = buildVoucher(LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusHours(1));
        mockQueryCount(0);
        mockStockUpdate(true);
        doAnswer(invocation -> {
            VoucherOrder order = invocation.getArgument(0);
            order.setId(123L);
            return true;
        }).when(voucherOrderService).save(any(VoucherOrder.class));
        when(seckillVoucherService.getById(1L)).thenReturn(voucher);

        Result result = voucherOrderService.seckillVoucher(1L);

        assertTrue(result.getSuccess());
        assertEquals(123L, result.getData());
    }

    private void mockQueryCount(int count) {
        doReturn(queryChainWrapper).when(voucherOrderService).query();
        when(queryChainWrapper.eq(anyString(), any())).thenReturn(queryChainWrapper);
        when(queryChainWrapper.count()).thenReturn(count);
    }

    private void mockStockUpdate(boolean success) {
        when(seckillVoucherService.update()).thenReturn(updateChainWrapper);
        when(updateChainWrapper.setSql(anyString())).thenReturn(updateChainWrapper);
        when(updateChainWrapper.eq(anyString(), any())).thenReturn(updateChainWrapper);
        when(updateChainWrapper.gt(anyString(), any())).thenReturn(updateChainWrapper);
        when(updateChainWrapper.update()).thenReturn(success);
    }

    private SeckillVoucher buildVoucher(LocalDateTime beginTime, LocalDateTime endTime) {
        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(1L);
        voucher.setStock(10);
        voucher.setBeginTime(beginTime);
        voucher.setEndTime(endTime);
        return voucher;
    }
}
