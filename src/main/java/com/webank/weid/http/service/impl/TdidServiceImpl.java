package com.webank.weid.http.service.impl;

import com.webank.weid.constant.ErrorCode;
import com.webank.weid.http.dto.DataPanel;
import com.webank.weid.http.service.TdidService;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.rpc.AuthorityIssuerService;
import com.webank.weid.rpc.CptService;
import com.webank.weid.rpc.WeIdService;
import com.webank.weid.service.impl.AuthorityIssuerServiceImpl;
import com.webank.weid.service.impl.CptServiceImpl;
import com.webank.weid.service.impl.WeIdServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TdidServiceImpl implements TdidService {
    private WeIdService weIdService;
    private CptService cptService;
    private AuthorityIssuerService authorityIssuerService;

    private WeIdService getWeIdService() {
        if (weIdService == null) {
            weIdService = new WeIdServiceImpl();
        }
        return weIdService;
    }
    private CptService getCptService() {
        if (cptService == null) {
            cptService = new CptServiceImpl();
        }
        return cptService;
    }
    private AuthorityIssuerService getAuthorityIssuerService() {
        if (authorityIssuerService == null) {
            authorityIssuerService = new AuthorityIssuerServiceImpl();
        }
        return authorityIssuerService;
    }
    @Override
    public ResponseData<DataPanel> getDataPanel() {
        DataPanel dataPanel = DataPanel.builder()
                .blockNetworkCount(1)
                .blockNetworkType(1)
                .didCount(getWeIdService().getWeIdCount().getResult())
                .cptCount(getCptService().getCptCount().getResult())
                .certificatedAuthCount(1)
                .issueCptCount(getAuthorityIssuerService().getIssuerCount().getResult())
                .build();
        return new ResponseData<>(dataPanel,ErrorCode.SUCCESS);
    }
}
