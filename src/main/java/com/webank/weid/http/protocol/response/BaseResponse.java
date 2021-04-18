package com.webank.weid.http.protocol.response;

import lombok.Data;

@Data
public class BaseResponse<T> {
    private int code;
    private String message;
    private T data;

    public BaseResponse() {
        
    }

    public BaseResponse(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
