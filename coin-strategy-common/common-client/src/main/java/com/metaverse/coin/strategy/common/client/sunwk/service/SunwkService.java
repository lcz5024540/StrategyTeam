package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;
import com.metaverse.coin.strategy.common.model.sunwk.SunwkResponse;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;

public abstract class SunwkService {
    protected final SunwkApi sunwkApi;

    public SunwkService(SunwkApi sunwkApi) {
        this.sunwkApi = sunwkApi;
    }

    protected <T> T execute(Call<T> call, String errorMessage) {
        try {
            Response<T> response = call.execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body();
            }
            // If body is null or not successful, we might want to read error body
            String errorBody = "";
            if (response.errorBody() != null) {
                errorBody = response.errorBody().string();
            }
            throw new RuntimeException(errorMessage + ": " + response.code() + " " + response.message() + " " + errorBody);
        } catch (IOException e) {
            throw new RuntimeException(errorMessage, e);
        }
    }

    protected <T> T executeData(Call<SunwkResponse<T>> call, String errorMessage) {
        SunwkResponse<T> response = execute(call, errorMessage);
        if (response != null && response.isSuccess()) {
            return response.getData();
        }
        throw new RuntimeException(errorMessage + ": " + (response != null ? response.getCode() + " " + response.getMsg() : "Unknown error"));
    }
}
