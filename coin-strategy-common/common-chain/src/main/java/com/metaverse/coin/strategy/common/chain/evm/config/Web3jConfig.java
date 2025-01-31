package com.metaverse.coin.strategy.common.chain.evm.config;

import com.google.common.collect.Maps;
import com.metaverse.coin.strategy.common.base.constants.ErrorConstantEnum;
import com.metaverse.coin.strategy.common.base.exception.BusinessException;
import com.metaverse.coin.strategy.common.chain.evm.enums.Chain;
import com.metaverse.coin.strategy.common.chain.evm.utils.AESUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.CipherSuite;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static okhttp3.ConnectionSpec.CLEARTEXT;

@Data
@Configuration
@Slf4j
@ConfigurationProperties(prefix = "web3j")
public class Web3jConfig {

    private Map<String, Network> network;
    private String privateKey;

    private static final Map<String,Web3j> WEB3J_MAP = Maps.newHashMap();
    public static String PRIVATE_KEY;

    @PostConstruct
    public void init() {
        if(CollectionUtils.isEmpty(network)){
            return;
        }

        network.keySet().stream().forEach(key->{
            Network network = this.network.get(key);
            Web3j web3j = Web3j.build(this.createHttpService(network));
            WEB3J_MAP.put(key.toLowerCase(),web3j);
        });

        //签名解密
        PRIVATE_KEY = AESUtil.decrypt(privateKey);
    }

    public String getByChain(String chainName){
        return network.get(chainName).url;
    }

    public static Web3j getWeb3j(Chain chain){
        if(Objects.isNull(chain)){
            throw new BusinessException(ErrorConstantEnum.WEB3J_CONFIG_ERROR);
        }

        return WEB3J_MAP.get(chain.name().toLowerCase());
    }

    public HttpService createHttpService(Network network) {
        HttpService httpService = new HttpService(network.getUrl(), createOkHttpClient());
//        if (StringUtils.isNotBlank(network.getUserName()) && StringUtils.isNotBlank(network.getPassword())) {
//            httpService.addHeader("Authorization", okhttp3.Credentials.basic(network.getUserName(), network.getPassword()));
//        }
        return httpService;
    }

    public static OkHttpClient createOkHttpClient() {
        final OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectionSpecs(CONNECTION_SPEC_LIST);
        return builder
                .retryOnConnectionFailure(true)
                //连接超时
                .connectTimeout(60, TimeUnit.SECONDS)
                //读取超时
                .readTimeout(90, TimeUnit.SECONDS)
                //写超时
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    private static final CipherSuite[] INFURA_CIPHER_SUITES = new CipherSuite[]{
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,

            // Note that the following cipher suites are all on HTTP/2's bad cipher suites list.
            // We'll
            // continue to include them until better suites are commonly available. For example,
            // none
            // of the better cipher suites listed above shipped with Android 4.4 or Java 7.
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA,

            // Additional INFURA CipherSuites
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA256
    };

    private static final ConnectionSpec INFURA_CIPHER_SUITE_SPEC = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .cipherSuites(INFURA_CIPHER_SUITES)
            .build();

    public static final List<ConnectionSpec> CONNECTION_SPEC_LIST = Arrays.asList(INFURA_CIPHER_SUITE_SPEC, CLEARTEXT);

    @Data
    private static class Network{
        private String url;
//        private String userName;
//        private String password;
    }
}
