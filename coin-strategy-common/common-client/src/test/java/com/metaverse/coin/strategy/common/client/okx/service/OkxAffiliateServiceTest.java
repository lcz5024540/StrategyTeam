package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.affiliate.OkxAffiliateInviteeDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OkxAffiliateServiceTest {

    @Mock
    private OkxApi okxApi;

    private OkxAffiliateService service;

    @Mock
    private Call<OkxRestResponse<OkxAffiliateInviteeDetail>> callDetail;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OkxAffiliateService(okxApi);
    }

    @Test
    public void testGetAffiliateInviteeDetail() throws IOException {
        when(okxApi.getAffiliateInviteeDetail(anyMap())).thenReturn(callDetail);
        when(callDetail.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxAffiliateInviteeDetail> response = service.getAffiliateInviteeDetail(Collections.emptyMap());
        assertNotNull(response);
    }
}
