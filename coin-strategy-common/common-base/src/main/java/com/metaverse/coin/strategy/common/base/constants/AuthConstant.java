package com.metaverse.coin.strategy.common.base.constants;

import lombok.experimental.Accessors;

@Accessors(chain = true)
public interface AuthConstant {
    int BEARER_BEGIN_INDEX = 7;

    String TOKEN_PREFIX = "Bearer ";
}
