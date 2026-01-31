package com.metaverse.coin.strategy.common.model.okx.entity.rest.account;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IOkxRestRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.IRestEntity;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OkxAccountConfig implements IRestEntity, IOkxRestRsp {
    @JSONField(name = "uid")
    @JsonProperty("uid")
    private String uid;

    @JSONField(name = "acctLv")
    @JsonProperty("acctLv")
    private String acctLv;

    @JSONField(name = "posMode")
    @JsonProperty("posMode")
    private String posMode;

    @JSONField(name = "autoLoan")
    @JsonProperty("autoLoan")
    private Boolean autoLoan;

    @JSONField(name = "greeksType")
    @JsonProperty("greeksType")
    private String greeksType;

    @JSONField(name = "level")
    @JsonProperty("level")
    private String level;

    @JSONField(name = "levelTmp")
    @JsonProperty("levelTmp")
    private String levelTmp;
    
    @JSONField(name = "mgnIsoMode")
    @JsonProperty("mgnIsoMode")
    private String mgnIsoMode;

    @JSONField(name = "spotOffsetType")
    @JsonProperty("spotOffsetType")
    private String spotOffsetType;
}
