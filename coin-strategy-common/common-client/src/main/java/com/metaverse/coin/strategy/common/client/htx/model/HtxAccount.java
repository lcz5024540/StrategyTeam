package com.metaverse.coin.strategy.common.client.htx.model;

import lombok.Data;

import java.util.List;

/**
 * HTX spot account descriptor (returned by /v1/account/accounts).
 * subtype is only meaningful for margin/otc/point accounts.
 */
@Data
public class HtxAccount {
    private long id;
    /** spot / margin / otc / point / super-margin */
    private String type;
    private String subtype;
    /** working / lock */
    private String state;
    private List<HtxBalance> list;
}
