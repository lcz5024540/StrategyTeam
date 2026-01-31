package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring.CancelRecurringBuyAlgoOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring.CancelRecurringBuyAlgoOrderRsp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OkxRecurringBuyServiceTest {

    @Mock
    private OkxApi okxApi;

    private OkxRecurringBuyService okxRecurringBuyService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        okxRecurringBuyService = new OkxRecurringBuyService(okxApi);
    }

    @Test
    void testStopRecurringBuyAlgoOrder() throws IOException {
        // Arrange
        String algoId = "123456";
        CancelRecurringBuyAlgoOrderReq req = CancelRecurringBuyAlgoOrderReq.builder()
                .algoId(algoId)
                .build();

        CancelRecurringBuyAlgoOrderRsp rspData = CancelRecurringBuyAlgoOrderRsp.builder()
                .algoId(algoId)
                .sCode("0")
                .sMsg("OK")
                .build();

        OkxRestResponse<CancelRecurringBuyAlgoOrderRsp> apiResponse = new OkxRestResponse<>();
        apiResponse.setCode(0);
        apiResponse.setMsg("OK");
        apiResponse.setData(java.util.Collections.singletonList(rspData));

        Call<OkxRestResponse<CancelRecurringBuyAlgoOrderRsp>> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(apiResponse));
        when(okxApi.stopRecurringBuyAlgoOrder(any(CancelRecurringBuyAlgoOrderReq.class))).thenReturn(call);

        // Act
        OkxRestResponse<CancelRecurringBuyAlgoOrderRsp> response = okxRecurringBuyService.stopRecurringBuyAlgoOrder(req);

        // Assert
        assertNotNull(response);
        assertEquals(0, response.getCode());
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());
        assertEquals(algoId, response.getData().get(0).getAlgoId());
    }
}
