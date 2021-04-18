package com.webank.weid.http.protocol.response;

import lombok.Data;
import lombok.ToString;

@Data
@ToString(callSuper = true)
public class BaseListResponse<T> extends BaseResponse<T> {

    private int totalCount;

    public BaseListResponse() {
        super();
    }

    public BaseListResponse(int code, String message) {
       super(code, message);
    }
}
