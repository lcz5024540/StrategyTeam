package com.metaverse.coin.strategy.common.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkeHttpException;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestError;
import io.reactivex.rxjava3.core.Single;
import retrofit2.HttpException;

import java.io.IOException;

public class ApiService {

    public static final ObjectMapper errorMapper = defaultObjectMapper();

    private static ObjectMapper defaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }

    /**
     * Calls the Open AI api, returns the response, and parses error messages if the request fails
     */
    protected static <T> T execute(Single<T> apiCall) {
        try {
            return apiCall.blockingGet();
        } catch (HttpException e) {
            try {
                if (e.response() == null || e.response().errorBody() == null) {
                    throw e;
                }
                String errorBody = e.response().errorBody().string();
                OkxRestError error = errorMapper.readValue(errorBody, OkxRestError.class);
                throw new OkeHttpException(error, error.getMsg(), e);
            } catch (IOException ex) {
                // couldn't parse OpenAI error
                throw e;
            }
        }
    }
}
