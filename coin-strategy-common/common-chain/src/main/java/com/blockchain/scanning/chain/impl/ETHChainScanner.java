package com.blockchain.scanning.chain.impl;

import cn.hutool.extra.spring.SpringUtil;
import com.blockchain.scanning.biz.thread.EventQueue;
import com.blockchain.scanning.biz.thread.model.EventModel;
import com.blockchain.scanning.chain.ChainScanner;
import com.blockchain.scanning.chain.model.TransactionModel;
import com.blockchain.scanning.commons.util.StringUtil;
import com.blockchain.scanning.config.BlockChainConfig;
import com.blockchain.scanning.config.EventConfig;
import com.blockchain.scanning.monitor.EthMonitorEvent;
import com.blockchain.scanning.monitor.filter.EthMonitorFilter;
import com.metaverse.coin.strategy.common.chain.evm.cache.BlockCache;
import com.metaverse.coin.strategy.common.chain.evm.cache.EthBlockCache;
import com.metaverse.coin.strategy.common.dao.chain.bean.Block;
import com.metaverse.coin.strategy.common.dao.chain.service.BlockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ETHChainScanner extends ChainScanner {
    private Logger logger = LoggerFactory.getLogger(ETHChainScanner.class);
    private Web3j web3j;
    private List<EthMonitorEvent> ethMonitorEventList = EventConfig.getEthMonitorEvent();
    private EthBlockCache ethBlockCache;

    public ETHChainScanner() {
    }

    public void init(BlockChainConfig blockChainConfig, EventQueue eventQueue) {
        super.init(blockChainConfig, eventQueue);
        this.web3j = Web3j.build(new HttpService(blockChainConfig.getRpcUrl()));
        ethBlockCache = SpringUtil.getApplicationContext().getBean(EthBlockCache.class);
    }

    public void scan(BigInteger beginBlockNumber, BigInteger endBlockNumber) {
        try {
            this.logger.info("scan start!beginBlockN={},endBlock={},threadId={}",beginBlockNumber,endBlockNumber,Thread.currentThread().getId());
            BigInteger blockNumber = ((EthBlockNumber)this.web3j.ethBlockNumber().send()).getBlockNumber();
            if (beginBlockNumber.compareTo(blockNumber) > 0) {
                this.logger.error("The starting block height has exceeded the current maximum block height, please reset");
                return;
            }

            if (endBlockNumber.compareTo(blockNumber) > 0) {
                endBlockNumber = blockNumber;
            }

            //开启一轮的多线程获取EthBlock
            ethBlockCache.pull(beginBlockNumber, endBlockNumber);

            for(BigInteger i = beginBlockNumber; i.compareTo(endBlockNumber) <= 0; i = i.add(BigInteger.ONE)) {
                this.blockChainConfig.setBeginBlockNumber(i.add(BigInteger.ONE));
                EthBlock block = ethBlockCache.get(i);
                if (block != null && block.getBlock() != null) {
                    this.eventQueue(block, i);
                } else {
                    block = this.web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(i), true).send();
                    if(block != null){
                        this.eventQueue(block, i);
                    }else{
                        Thread.sleep(500L);
                    }
                }
            }
        } catch (Exception var11) {
            this.logger.error("An exception occurred while scanning", var11);
        }
    }

    private void eventQueue(EthBlock block,BigInteger curBlockNumber){
        //最新block入库
        this.saveBlock(block);
        List<EthBlock.TransactionResult> transactionResultList = block.getBlock().getTransactions();
        if (transactionResultList != null && transactionResultList.size() >= 1) {
            List<TransactionModel> transactionList = new ArrayList();
            Iterator var8 = transactionResultList.iterator();

            while(var8.hasNext()) {
                EthBlock.TransactionResult<EthBlock.TransactionObject> transactionResult = (EthBlock.TransactionResult)var8.next();
                EthBlock.TransactionObject transactionObject = (EthBlock.TransactionObject)transactionResult.get();
                transactionList.add(TransactionModel.builder().setEthTransactionModel(transactionObject));
            }

            this.eventQueue.add(EventModel.builder().setCurrentBlockHeight(curBlockNumber).setTransactionModels(transactionList));
        }
    }

    private void saveBlock(EthBlock block){
        try {
            BigInteger blockHeight = block.getBlock().getNumber();
            if(!BlockCache.isExist(blockHeight,true)){
                Block newBlock = new Block();
                newBlock.setBlockHeight(blockHeight);
                newBlock.setBlockHash(block.getBlock().getHash());
                newBlock.setPreviousHash(block.getBlock().getParentHash());
                //TODO
//                boolean result = blockService.saveOrUpdate(newBlock);
//                if(result){
//                    logger.info("saveBlock success!blockNum={}",blockHeight);
//                }else{
//                    logger.error("saveBlock fail!blockNum={}",blockHeight);
//                }
            }
        } catch (Exception e) {
            logger.error("saveBlock error!blockNum={}", block.getBlock().getNumber(),e);
        }
    }

    public void call(TransactionModel transactionModel) {
        EthBlock.TransactionObject transactionObject = transactionModel.getEthTransactionModel();
        Iterator var3 = this.ethMonitorEventList.iterator();

        while(true) {
            EthMonitorEvent ethMonitorEvent;
            while(true) {
                if (!var3.hasNext()) {
                    return;
                }

                ethMonitorEvent = (EthMonitorEvent)var3.next();
                EthMonitorFilter ethMonitorFilter = ethMonitorEvent.ethMonitorFilter();
                if (ethMonitorFilter == null) {
                    break;
                }

                if (transactionModel.getEthTransactionModel().getValue() == null) {
                    transactionModel.getEthTransactionModel().setValue("0");
                }

                if ((!StringUtil.isNotEmpty(ethMonitorFilter.getFromAddress()) || !StringUtil.isEmpty(transactionObject.getFrom()) && ethMonitorFilter.getFromAddress().equals(transactionObject.getFrom().toLowerCase())) && (!StringUtil.isNotEmpty(ethMonitorFilter.getToAddress()) || !StringUtil.isEmpty(transactionObject.getTo()) && ethMonitorFilter.getToAddress().equals(transactionObject.getTo().toLowerCase())) && (ethMonitorFilter.getMinValue() == null || ethMonitorFilter.getMinValue().compareTo(transactionModel.getEthTransactionModel().getValue()) <= 0) && (ethMonitorFilter.getMaxValue() == null || ethMonitorFilter.getMaxValue().compareTo(transactionModel.getEthTransactionModel().getValue()) >= 0)) {
                    if (!StringUtil.isNotEmpty(ethMonitorFilter.getFunctionCode())) {
                        break;
                    }

                    if (!StringUtil.isEmpty(transactionObject.getInput()) && transactionObject.getInput().length() >= 10) {
                        if (!transactionObject.getInput().toLowerCase().startsWith("0x")) {
                            transactionObject.setInput("0x" + transactionObject.getInput());
                        }

                        if (transactionObject.getInput().toLowerCase().startsWith(ethMonitorFilter.getFunctionCode())) {
                            break;
                        }
                    }
                }
            }

            ethMonitorEvent.call(transactionModel);
        }
    }
}
