package com.metaverse.coin.strategy.common.client.gate.service;

import com.metaverse.coin.strategy.common.client.gate.GateApi;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;

public abstract class GateService {
    protected final GateApi gateApi;

    public GateService(GateApi gateApi) {
        this.gateApi = gateApi;
    }

    protected <T> T execute(Call<T> call) {
        try {
            Response<T> response = call.execute();
            if (response.isSuccessful()) {
                return response.body();
            } else {
                throw new RuntimeException("Gate API Error: " + response.code() + " " + response.errorBody().string());
            }
        } catch (IOException e) {
            throw new RuntimeException("Network error", e);
        }
    }
}
