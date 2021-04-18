package com.webank.weid.http.controller;

import com.webank.weid.constant.ErrorCode;
import com.webank.weid.http.constant.DataFrom;
import com.webank.weid.http.constant.FileOperator;
import com.webank.weid.http.dto.*;
import com.webank.weid.http.service.TdidService;
import com.webank.weid.http.service.impl.*;
import com.webank.weid.http.util.FileUtils;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.service.BaseService;
import com.webank.weid.util.DataToolUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringEscapeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Description;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 *  Information form chain and tbass.
 *
 * @author garyding
 */
@Slf4j
@RestController
@RequestMapping(value = "/tencent/tdid/v1")
public class TdidController {

    @Resource
    private TdidService tdidService;

    @Autowired
    private WeIdSdkService weIdSdkService;

    @Autowired
    private ContractService contractService;

    @Description("查询数据概览")
    @GetMapping("/getDataPanel")
    public ResponseData<DataPanel> getDataPanel() {
        return tdidService.getDataPanel();
    }

    @GetMapping("/getWeIdList")
    public ResponseData<PageDto<WeIdInfo>> getWeIdList(
            @RequestParam(value = "blockNumber") int blockNumber,
            @RequestParam(value = "pageSize") int pageSize,
            @RequestParam(value = "indexInBlock") int indexInBlock,
            @RequestParam(value = "direction") boolean direction,
            @RequestParam(value = "iDisplayStart") int iDisplayStart,
            @RequestParam(value = "iDisplayLength") int iDisplayLength
    ) {
        if (blockNumber == 0) {
            try {
                blockNumber = BaseService.getBlockNumber();
            } catch (IOException e) {
                log.error("[getWeIdList] get blockNumber fail.", e);
                return new ResponseData<>(null, ErrorCode.BASE_ERROR);
            }
        }
        PageDto<WeIdInfo> pageDto = new PageDto<WeIdInfo>(iDisplayStart, iDisplayLength);
        return weIdSdkService.getWeIdList(pageDto, blockNumber, pageSize, indexInBlock, direction);
    }

    @Description("查询issuer type列表")
    @GetMapping("/getIssuerTypeList")
    public ResponseData<List<IssuerType>> getIssuerTypeList() {
        log.info("begin getIssuerTypeList.");
        return new ResponseData<>(weIdSdkService.getIssuerTypeList(), ErrorCode.SUCCESS);
    }

    @Description("系统自动生成公私钥生成weId")
    @GetMapping("/createTDid")
    public ResponseData<String> createWeId() {
        log.info("[createWeId] begin create weid...");
        return weIdSdkService.createWeId(DataFrom.WEB_BY_DEFAULT);
    }

    @Description("根据传入的私钥创建weId")
    @PostMapping("/createWeIdByPrivateKey")
    public ResponseData<String> createWeIdByPrivateKey(HttpServletRequest request) {
        log.info("[createWeIdByPrivateKey] begin create weid...");
        return weIdSdkService.createWeIdByPrivateKey(request, DataFrom.WEB_BY_PRIVATE_KEY);
    }

    @Description("根据传入的公钥代理创建weId")
    @PostMapping("/createWeIdByPublicKey")
    public ResponseData<String> createWeIdByPublicKey(HttpServletRequest request) {
        log.info("[createWeIdByPublicKey] begin create weid...");
        return weIdSdkService.createWeIdByPublicKey(request, DataFrom.WEB_BY_PUBLIC_KEY);
    }



    @Description("注册issuer")
    @PostMapping("/registerIssuer")
    public ResponseData<Boolean> registerIssuer(@RequestParam("weId") String weId,
                                                @RequestParam("name") String name,
                                                @RequestParam("description") String description) {
        log.info("begin register issuer, weId:{}, name:{}, description:{}", weId, name, description);
        description = StringEscapeUtils.unescapeHtml(description);
        return weIdSdkService.registerIssuer(weId, name, description);
    }

    @Description("认证issuer")
    @PostMapping("/recognizeAuthorityIssuer")
    public ResponseData<Boolean> recognizeAuthorityIssuer(@RequestParam("weId") String weId) {
        log.info("begin recognize authority issuer, weId:{}", weId);
        return weIdSdkService.recognizeAuthorityIssuer(weId);
    }

    @Description("撤销认证issuer")
    @PostMapping("/deRecognizeAuthorityIssuer")
    public ResponseData<Boolean> deRecognizeAuthorityIssuer(@RequestParam("weId") String weId) {
        log.info("begin deRecognize authority issuer, weId:{}", weId);
        return weIdSdkService.deRecognizeAuthorityIssuer(weId);
    }

    @GetMapping("/getIssuerList")
    public ResponseData<PageDto<Issuer>> getIssuerList(
            @RequestParam(value = "iDisplayStart") int iDisplayStart,
            @RequestParam(value = "iDisplayLength") int iDisplayLength
    ) {
        log.info("begin get issuer list, iDisplayStart:{}, iDisplayLength:{}", iDisplayStart, iDisplayLength);
        PageDto<Issuer> pageDto = new PageDto<Issuer>(iDisplayStart, iDisplayLength);
        return weIdSdkService.getIssuerList(pageDto);
    }

    @Description("CPT注册")
    @PostMapping("/registerCpt")
    public ResponseData<Boolean> registerCpt(HttpServletRequest request) {
        log.info("[registerCpt] begin save the cpt json file...");
        String cptJson = request.getParameter("cptJson");
        cptJson = StringEscapeUtils.unescapeHtml(cptJson);
        String fileName = DataToolUtils.getUuId32();
        File targetFIle = new File("output/", fileName + ".json");
        FileUtils.writeToFile(cptJson, targetFIle.getAbsolutePath(), FileOperator.OVERWRITE);
        log.info("[registerCpt] begin register cpt...");
        String cptId = request.getParameter("cptId");
        try {
            //判断当前账户是否注册成weid，如果没有则创建weid
            contractService.createWeIdForCurrentUser(DataFrom.WEB);
            return weIdSdkService.registerCpt(targetFIle, cptId, DataFrom.WEB);
        } finally {
            FileUtils.delete(targetFIle);
        }
    }

    @GetMapping("/getCptInfoList")
    public ResponseData<PageDto<CptInfo>> getCptInfoList(
            @RequestParam(value = "iDisplayStart") int iDisplayStart,
            @RequestParam(value = "iDisplayLength") int iDisplayLength,
            @RequestParam(value = "cptType") String cptType
    ) {
        log.info("begin getCptInfoList, iDisplayStart:{}, iDisplayLength:{}, cptType:{}",
                iDisplayStart, iDisplayLength, cptType);
        PageDto<CptInfo> pageDto = new PageDto<CptInfo>(iDisplayStart, iDisplayLength);
        pageDto.setQuery(new CptInfo());
        pageDto.getQuery().setCptType(cptType);
        return weIdSdkService.getCptList(pageDto);
    }

    @Description("获取cpt Schema信息")
    @GetMapping("/queryCptSchema/{cptId}")
    public ResponseData<String> queryCptSchema(@PathVariable("cptId") Integer cptId) {
        log.info("begin queryCptSchema, cptId:{}", cptId);
        return weIdSdkService.queryCptSchema(cptId);
    }

}
