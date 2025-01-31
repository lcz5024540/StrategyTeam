package com.metaverse.coin.strategy.common.dao.chain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metaverse.coin.strategy.common.dao.chain.bean.Block;
import com.metaverse.coin.strategy.common.dao.chain.mapper.BlockMapper;
import com.metaverse.coin.strategy.common.dao.chain.service.BlockService;
import org.springframework.stereotype.Service;

import java.math.BigInteger;

@Service
public class BlockServiceImpl extends ServiceImpl<BlockMapper, Block> implements BlockService {


    @Override
    public Block getLastBlock() {
        LambdaQueryWrapper<Block> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.orderByDesc(Block::getBlockHeight);
        lambdaQueryWrapper.last("limit 1");
        return getOne(lambdaQueryWrapper);
    }

    @Override
    public Block getByHeight(BigInteger blockHeight) {
        LambdaQueryWrapper<Block> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(Block::getBlockHeight,blockHeight);
        return getOne(lambdaQueryWrapper);
    }
}
