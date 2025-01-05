package com.metaverse.coin.strategy.web.controller;

import com.metaverse.coin.strategy.common.client.okx.OkxApiService;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.Balance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/okx")
public class OkxController {

    @Autowired
    private OkxApiService okxApiService;

    @GetMapping("/getBalance")
    public OkxRestResponse<Balance> getBalance(@RequestParam String cc){
        return okxApiService.getBalance(cc);
    }
}
