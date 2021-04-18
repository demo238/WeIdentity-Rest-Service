package com.webank.weid.http.dto;

import lombok.Data;

@Data
public class ContractSolInfo {

    private String contractName;
    private String contractSource;
    private String contractAbi;
    private String bytecodeBin;
}
