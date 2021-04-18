package com.webank.weid.http.service;

import com.webank.weid.http.dto.DataPanel;
import com.webank.weid.protocol.response.ResponseData;

public interface TdidService {
    ResponseData<DataPanel> getDataPanel();
}
