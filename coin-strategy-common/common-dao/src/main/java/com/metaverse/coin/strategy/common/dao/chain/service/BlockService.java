package com.metaverse.coin.strategy.common.dao.chain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.metaverse.coin.strategy.common.dao.chain.bean.Block;

import java.math.BigInteger;

public interface BlockService extends IService<Block> {

    Block getLastBlock();
    Block getByHeight(BigInteger blockHeight);
}