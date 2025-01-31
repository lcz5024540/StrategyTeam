package com.metaverse.coin.strategy.common.chain.evm.utils;

import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.common.base.constants.ErrorConstantEnum;
import com.metaverse.coin.strategy.common.base.exception.BusinessException;
import com.metaverse.coin.strategy.common.chain.evm.config.Web3jConfig;
import com.metaverse.coin.strategy.common.chain.evm.enums.Chain;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public class Web3jChainUtils {

    public static TransactionReceipt getTxInfo(Chain chain, String txHash) {
        try {
            EthGetTransactionReceipt ethReceipt = getTransactionReceipt(chain,txHash);
            Optional<TransactionReceipt> tr =  ethReceipt.getTransactionReceipt();
            if (tr != null && tr.isPresent()) {
                return tr.get();
            }
        } catch (Exception e) {
            log.error("getResult error,txHash={}", txHash, e);
        }

        return null;
    }

    public static List<EthLog.LogResult> getEthLog(Chain chain, String address, Long fromBlock, Long toBlock, String topic0) {
        Web3j web3j = Web3jConfig.getWeb3j(chain);

        EthFilter ethFilter = new EthFilter(
                DefaultBlockParameter.valueOf(new BigInteger(fromBlock.toString())),
                DefaultBlockParameter.valueOf(new BigInteger(toBlock.toString())),
                address
        );
        if (StringUtils.isNotBlank(topic0)) {
            ethFilter.addSingleTopic(topic0);
        }

        try {
            EthLog ethLog = web3j.ethGetLogs(ethFilter).send();
            if (ethLog.hasError()) {
                log.warn("query eth log was wrong, chain: {}, address: {}, errorMsg: {}", chain, address, ethLog.getError().getMessage());
                return null;
            }
            return ethLog.getLogs();
        } catch (Exception e) {
            log.error("query eth log has error, chain: " + chain + ", address: " + address, e);
            return null;
        }
    }

    private static EthGetTransactionReceipt getTransactionReceipt(Chain chian, String txHash) throws Exception {
        Web3j web3j = Web3jConfig.getWeb3j(chian);
        return web3j
                .ethGetTransactionReceipt(txHash)
                .sendAsync()
                .get();
    }

    public static BigInteger getGasPrice(Chain chain) throws Exception {
        Web3j web3j = Web3jConfig.getWeb3j(chain);
        return web3j.ethGasPrice().send().getGasPrice();
    }

    public static BigInteger getNonce(Chain chain, String address) throws Exception {
        Web3j web3j = Web3jConfig.getWeb3j(chain);
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING).send().getTransactionCount();
    }

    public static String getSingleOutput(Chain chain, String contract, String method) {
        return getSingleInputOutput(chain,contract, method, Collections.emptyList());
    }

    public static String getSingleInputOutput(Chain chain, String contract, String method, List<Type> inputs) {
        List<TypeReference<?>> outputParams = Collections.singletonList(new TypeReference<Uint256>(){});
        List<Type> result = read(chain,contract, method, inputs, outputParams);
        return CollectionUtils.isEmpty(result) ? null : result.get(0).getValue().toString();
    }

    public static BigInteger getBalance(Chain chain, String account) {
        Web3j web3j = Web3jConfig.getWeb3j(chain);
        EthGetBalance ethGetBalance;
        try {
            ethGetBalance = web3j.ethGetBalance(account, DefaultBlockParameterName.LATEST).send();
        } catch (Exception e) {
            log.warn("cmd=getBalance, web3j call method[getBalance] has error, chain={}, address={}", chain, account);
            throw new BusinessException(ErrorConstantEnum.WEB3J_EXECUTE_ERROR, "web3j call method[getBalance] has error: " + e.getMessage());
        }
        if (null == ethGetBalance.getResult()) {
            log.warn("cmd=getBalance,the balance's result is null,chain={}, address={}", chain, account);
            throw new BusinessException(ErrorConstantEnum.WEB3J_EXECUTE_ERROR, "the balance's result is null,probably the request params is wrong");
        }

        return ethGetBalance.getBalance();
    }

    public static BigInteger getLatestBlockNumber(Chain chain) {
        EthBlock latestBlock = getLatestBlock(chain);
        if (latestBlock == null) {
            return BigInteger.ZERO;
        }
        return latestBlock.getBlock().getNumber();
    }

    public static EthBlock getLatestBlock(Chain chain) {
        Web3j web3j = Web3jConfig.getWeb3j(chain);
        try {
            return web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send();
        }catch(Exception e) {
            log.error("web3j call method[getLatestBlock] has error",e);
        }
        return null;
    }

    public static EthBlock getBlockByBlockNumber(Chain chain, BigInteger blockNum) {
        Web3j web3j = Web3jConfig.getWeb3j(chain);
        try {
            return web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(blockNum), true).send();
        }catch(Exception e) {
            log.error("web3j call method[getBlockByBlockNumber] has error",e);
        }
        return null;
    }

    public static EthFilter getEthFilter(BigInteger blockHeight,String contract){
        return new EthFilter(DefaultBlockParameter.valueOf(blockHeight),DefaultBlockParameterName.LATEST,contract);
    }

    /**
     *
     * 功能描述: 合约查询（不消耗gas）
     *
     * @param chain： 链
     * @param contract：合约地址
     * @param functionName：方法名
     * @param inputs：合约入参
     * @param outputs：合约出参
     * @return: java.util.List<org.web3j.abi.datatypes.Type>
     * @date: 2023/7/8 18:45
     */
    public static List<Type> read(Chain chain, String contract, String functionName, List<Type> inputs, List<TypeReference<?>> outputs) {
        Web3j web3j = Web3jConfig.getWeb3j(chain);
        try {
            Function function = new Function(functionName
                    , inputs
                    , outputs);
            String encodedFunction = FunctionEncoder.encode(function);

            Transaction transaction = Transaction.createEthCallTransaction(null, contract, encodedFunction);
            EthCall response = web3j
                    .ethCall(transaction, DefaultBlockParameterName.LATEST)
                    .sendAsync()
                    .get();

            if (null == response.getValue()) {
                return Collections.emptyList();
            }

            return FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        } catch (Exception e) {
            log.warn("web3j call has error: " + contract + "[" + functionName + "]", e);
            throw new BusinessException(ErrorConstantEnum.WEB3J_EXECUTE_ERROR, "web3j call method[" + functionName + "] has error: " + e.getMessage());
        }
    }

    /**
     *
     * 功能描述: 合约写入（消耗gas）
     *
     * @param chain：链
     * @param contract：合约地址
     * @param functionName：方法名
     * @param inputs：合约入参
     * @param outputs：合约出参
     * @return: java.lang.String
     * @date: 2023/7/8 18:46
     */
    public static String write(Chain chain, String contract, String functionName, List<Type> inputs, List<TypeReference<?>> outputs) throws Exception {
        Web3j web3j = Web3jConfig.getWeb3j(chain);

        // 转换value的单位
        BigInteger valueWei = Convert.toWei("0", Convert.Unit.ETHER).toBigInteger();
        // 生成需要调用函数的data
        Function function = new Function(functionName, inputs, outputs);
        String data = FunctionEncoder.encode(function);
        // 估算gasLimit
        //BigInteger gasLimit = new BigInteger("500000");
        BigInteger gasLimit = new BigInteger("3000000");
        // 获取gasPrice
        BigInteger gasPrice = getGasPrice(chain).multiply(new BigInteger("20"));
        // 正式请求
        Credentials credentials = getCredentials();
        BigInteger nonce = getNonce(chain ,credentials.getAddress());
        log.info("nonce:{}, Gas fee: {} ETH", nonce, Convert.fromWei(String.valueOf(gasLimit.multiply(gasPrice)), Convert.Unit.ETHER));
        RawTransaction rawTransaction = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, contract, valueWei, data);
        // 签名数据
        byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, chain.getChainId(), credentials);
        String hexValue = Numeric.toHexString(signedMessage);
        // 发送数据
        EthSendTransaction response = web3j.ethSendRawTransaction(hexValue).send();
        // 查看是否有错误
        if (response.hasError()) {
            log.error("trade hash: {} ,error: {}", response.getTransactionHash(), response.getError().getMessage());
            throw new Exception("trade hash: " + response.getTransactionHash() +
                    "\nerror: " + response.getError().getMessage());
        }
        String txHash = response.getTransactionHash();
        log.info("Trade Hash: {}", txHash);

        EthGetTransactionReceipt txReceiptResp = getTransactionReceipt(chain,txHash);
        TransactionReceipt txReceipt = txReceiptResp.getResult();
        log.info("transactionReceipt : {}" , JSONObject.toJSONString(txReceiptResp));
        if (txReceiptResp.hasError()) {
            log.info("获取交易状态出错 :{}", txHash);
            return null;
        }
        if (Objects.isNull(txReceipt)) {
            log.info("txReceipt is null");
            return txHash;
        }
        //交易失败
        if (!txReceipt.isStatusOK()) {
            log.info("交易状态错误 {}:{}", txHash, !txReceipt.isStatusOK());
            return null;
        }
        if(StringUtils.isEmpty(response.getTransactionHash())){
            return txHash;
        }else{
            return response.getTransactionHash();
        }
    }


    public static Credentials getCredentials(){
        String privateKey = Web3jConfig.PRIVATE_KEY;
        if (StringUtils.isEmpty(privateKey)) {
            throw new BusinessException(ErrorConstantEnum.PRIVATE_KEY_ERROR);
        }
        //这个Web3Util 是自己定义的工具，能根据userId和siteId生成Credentials 对象的工具，大家可以自己构造，我这个有其他依赖，不方便提供源码
        Credentials credentials = Credentials.create(privateKey);
        log.info("运营钱包地址为:{}", credentials.getAddress());
        return credentials;
    }
}
