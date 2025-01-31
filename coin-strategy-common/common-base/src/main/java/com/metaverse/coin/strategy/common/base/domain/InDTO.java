package com.metaverse.coin.strategy.common.base.domain;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 国际化对象
 */
@Data
@ApiModel(value = "国际化对象", description = "国际化对象")
public class InDTO implements Serializable {
    @ApiModelProperty("中文简体")
    @NotBlank(message = "zhName不能为空")
    private String zh;
    @ApiModelProperty("中文繁体")
    @NotBlank(message = "twName不能为空")
    private String tw;
    @ApiModelProperty("英文")
    @NotBlank(message = "enName不能为空")
    private String en;
    @ApiModelProperty("日文")
    @NotBlank(message = "jpName不能为空")
    private String jp;
    @ApiModelProperty("土耳其")
    @NotBlank(message = "trName不能为空")
    private String tr;

    public InDTO() {
    }

    public InDTO(String zh, String tw, String en, String jp) {
        this.zh = zh;
        this.tw = tw;
        this.en = en;
        this.jp = jp;
    }

    public InDTO(String zh, String tw, String en, String jp, String tr) {
        this.zh = zh;
        this.tw = tw;
        this.en = en;
        this.jp = jp;
        this.tr = tr;
    }
}
