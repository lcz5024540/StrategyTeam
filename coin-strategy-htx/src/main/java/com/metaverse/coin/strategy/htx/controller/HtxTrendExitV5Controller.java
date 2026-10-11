package com.metaverse.coin.strategy.htx.controller;

import com.metaverse.coin.strategy.htx.service.HtxTrendExitV5QueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Default-disabled, read-only v5 explanation endpoint. */
@RestController
@RequestMapping("/strategy/api/htx-trend")
@ConditionalOnProperty(name="htx.trend.exit-v5-query-enabled",havingValue="true")
public class HtxTrendExitV5Controller {
    private final HtxTrendExitV5QueryService query;
    public HtxTrendExitV5Controller(HtxTrendExitV5QueryService query){this.query=query;}
    @GetMapping("/positions/{id}/exit-v5")
    public HtxTrendExitV5QueryService.View position(@PathVariable long id,@RequestParam String accountLabel){try{return query.get(id,accountLabel);}catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"v5 position not found");}}
}
