package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;

public abstract class OkxService {
    protected final OkxApi okxApi;

    public OkxService(OkxApi okxApi) {
        this.okxApi = okxApi;
    }

    protected <T> T execute(Call<T> call, String errorMessage) {
        try {
            Response<T> response = call.execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body();
            }
            throw new RuntimeException(errorMessage + ": " + response.message());
        } catch (IOException e) {
            throw new RuntimeException(errorMessage, e);
        }
    }
}
