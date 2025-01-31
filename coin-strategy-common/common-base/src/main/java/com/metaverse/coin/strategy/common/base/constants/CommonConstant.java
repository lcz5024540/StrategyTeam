package com.metaverse.coin.strategy.common.base.constants;

public interface CommonConstant {

    /**
     * token请求头名称
     */
    String REQ_HEADER = "Authorization";

    /**
     * token分割符
     */
    String TOKEN_SPLIT = "Bearer ";

    /**
     * jwt签名
     */
    String SIGN_KEY = "ATOM";

    /**
     * jwt签名
     */
    String SIGN_KEY_OUT = "ATOM_OUT";

    /**
     * 删除
     */
    String STATUS_DEL = "1";
    /**
     * 正常
     */
    String STATUS_NORMAL = "0";


    /**
     * 菜单
     */
    String MENU = "0";

    /**
     * 菜单
     */
    String HIDDEN_MENU = "2";

    /**
     * 删除标记
     */
    String DEL_FLAG = "del_flag";

    int RANDOM_LENGTH = 18;
    /**
     * 买盲盒和NFT
     */
    String BUY_ASSET = "buyAsset";
    /**
     * 开盲盒
     */
    String OPEN_BLIND_BOX = "openBlindBox";
    /**
     * 监听NFT合约
     */
    String ERC1155_GAMEFI = "ERC1155_GAMEFI";
    /**
     * 零地址
     * */
    String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
}
