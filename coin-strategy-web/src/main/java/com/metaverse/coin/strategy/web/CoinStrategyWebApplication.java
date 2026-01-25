package com.metaverse.coin.strategy.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

//@SpringBootApplication
public class CoinStrategyWebApplication {

    public static void main(String[] args) {
//        SpringApplication.run(CoinStrategyWebApplication.class, args);
//        System.out.println("=========================================");
//        System.out.println("CoinStrategyWebApplication start success!");
//        System.out.println("=========================================");

        String s1 = new String("二哥三妹");
        String s2 = new String("二哥");
        String s3 = new String("三妹");
        String s4 = s2 + s3;
        System.out.println(s1.equals(s4));
    }

}
