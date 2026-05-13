package com.metaverse.coin.strategy.web.controller;

import com.metaverse.coin.strategy.common.client.okx.OkxApiService;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.Balance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OKX 相关接口。
 * <p>仅当 {@code client.okx.apiKey} 配置存在时才注册此 controller，
 * 避免 OkxApiService bean 缺失导致 Spring 启动失败。
 * 没配置 OKX 凭证的环境（本地 / 单纯 web 测试）/okx/** 直接 404，符合预期。</p>
 */
@RestController
@RequestMapping(value = "/okx")
@ConditionalOnProperty(name = "client.okx.apiKey")
public class OkxController {

    @Autowired
    private OkxApiService okxApiService;

    @GetMapping("/getBalance")
    public OkxRestResponse<Balance> getBalance(@RequestParam String cc){
        return okxApiService.getBalance(cc);
    }
}
