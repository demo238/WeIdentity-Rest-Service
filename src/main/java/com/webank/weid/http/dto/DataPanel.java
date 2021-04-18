package com.webank.weid.http.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DataPanel {
    /**
     * 区块链网络数量.
     */
    private Integer blockNetworkCount;
    /**
     * 区块链网络类型.
     */
    private Integer blockNetworkType;
    /**
     * DID数量.
     */
    private Integer didCount;
    /**
     * 凭证模版数量.
     */
    private Integer cptCount;
    /**
     * 已认证权威机构数量.
     */
    private Integer certificatedAuthCount;
    /**
     * 颁发凭证数量.
     */
    private Integer issueCptCount;
}
