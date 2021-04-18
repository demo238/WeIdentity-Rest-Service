package com.webank.weid.http.controller;

import com.webank.weid.config.ContractConfig;
import com.webank.weid.config.FiscoConfig;
import com.webank.weid.constant.CnsType;
import com.webank.weid.constant.ErrorCode;
import com.webank.weid.constant.WeIdConstant;
import com.webank.weid.contract.deploy.v2.DeployContractV2;
import com.webank.weid.exception.WeIdBaseException;
import com.webank.weid.http.constant.BuildToolsConstant;
import com.webank.weid.http.constant.DataFrom;
import com.webank.weid.http.dto.CnsInfo;
import com.webank.weid.http.dto.DeployInfo;
import com.webank.weid.http.service.impl.*;
import com.webank.weid.http.util.WeIdSdkUtils;
import com.webank.weid.protocol.base.WeIdPrivateKey;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.util.PropertyUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Description;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 配置管理
 */

@RestController
@RequestMapping(value = "/tencent/tdid/config/")
@Slf4j
public class ConfigurationController {

	@Autowired
	private ConfigService configService;

	@Autowired
	private WeIdSdkService weIdSdkService;

	@Autowired
	private ContractService contractService;

	@Autowired
	private GuideService guideService;

	@Description("加载run.config配置")
	@GetMapping("/loadConfig")
	public ResponseData<Map<String, String>> loadConfig() {
		log.info("start loadConfig.");
		return new ResponseData<>(configService.loadConfig(), ErrorCode.SUCCESS);
	}

	@Description("获取用户角色")
	@GetMapping("/getRole")
	public ResponseData<String> getRole() {
		log.info("start getRole.");
		return configService.getRoleType();
	}

	@Description("设置用户角色")
	@PostMapping("/setRole")
	public ResponseData<Boolean> setRole(@RequestParam(value = "roleType") String roleType) {
		log.info("start setRole. roleType:{}", roleType);
		return new ResponseData<>(guideService.setRoleType(roleType), ErrorCode.SUCCESS);
	}

	@Description("节点配置提交")
	@PostMapping("/nodeConfigUpload")
	public ResponseData<Boolean> nodeConfigUpload(HttpServletRequest request) {
		log.info("[nodeConfigUpload] begin upload file...");
		return configService.nodeConfigUpload(request);
	}

	@Description("提交群组Id")
	@PostMapping("/setGroupId")
	public ResponseData<Boolean> setMasterGroupId(@RequestParam("groupId") String groupId) {
		log.info("[setMasterGroupId] begin set the groupId = {}.", groupId);
		boolean result = configService.setMasterGroupId(groupId);
		PropertyUtils.reload();
		return new ResponseData<>(result, ErrorCode.SUCCESS);
	}

	@Description("查询群组列表")
	@GetMapping("/getGroupMapping")
	public ResponseData<List<Map<String, String>>> getGroupMapping() {
		log.info("start get group mapping.");
		return weIdSdkService.getGroupMapping();
	}

	@PostMapping("/deploy")
	public ResponseData<String> deploy(
			@RequestParam("chainId")  String chainId,
			@RequestParam(BuildToolsConstant.APPLY_NAME) String applyName
	) {
		log.info("[deploy] begin load fiscoConfig...");
		return contractService.deploy(chainId, applyName);
	}

	@GetMapping("/getDeployList")
	public ResponseData<LinkedList<CnsInfo>> getDeployList() {
		log.info("start getDeployList.");
		return contractService.getDeployList();
	}

	@GetMapping("/getDeployInfo")
	public ResponseData<DeployInfo> getDeployInfo(@RequestParam("hash") String hash) {
		log.info("start getDeployInfo, hash:{}", hash);
		return new ResponseData<>(contractService.getDeployInfoByHashFromChain(hash), ErrorCode.SUCCESS);
	}

	@GetMapping("/removeHash/{hash}/{type}")
	public ResponseData<Boolean> removeHash(@PathVariable("hash") String hash, @PathVariable("type") Integer type){
		log.info("start removeHash, hash:{}, type:{}", hash, type);
		if (type != 1 && type != 2) {
			log.error("[removeHash] the type error, type = {}.", type);
			return new ResponseData<>(Boolean.FALSE, ErrorCode.BASE_ERROR.getCode(), "the type error");
		}

		CnsType cnsType = (type == 1) ? CnsType.DEFAULT : CnsType.SHARE;
		return contractService.removeHash(cnsType, hash);
	}

	@GetMapping("/enableHash")
	public ResponseData<Boolean> enableHash(@RequestParam("hash") String hash) {
		log.info("[enableHash] begin load fiscoConfig...");
		try {
			//  获取老Hash
			String  oldHash = WeIdSdkUtils.getMainHash();
			// 获取原配置
			FiscoConfig fiscoConfig = WeIdSdkUtils.loadNewFiscoConfig();
			WeIdPrivateKey currentPrivateKey = WeIdSdkUtils.getWeIdPrivateKey(hash);

			// 获取部署数据
			DeployInfo deployInfo = contractService.getDeployInfoByHashFromChain(hash);
			ContractConfig contract = new ContractConfig();
			contract.setWeIdAddress(deployInfo.getWeIdAddress());
			contract.setIssuerAddress(deployInfo.getAuthorityAddress());
			contract.setSpecificIssuerAddress(deployInfo.getSpecificAddress());
			contract.setEvidenceAddress(deployInfo.getEvidenceAddress());
			contract.setCptAddress(deployInfo.getCptAddress());
			if (StringUtils.isNotBlank(deployInfo.getChainId())) {
				fiscoConfig.setChainId(deployInfo.getChainId());
			} else {
				//兼容历史数据
				fiscoConfig.setChainId(configService.loadConfig().get("chain_id"));
			}
			// 写入全局配置中
			DeployContractV2.putGlobalValue(fiscoConfig, contract, currentPrivateKey);
			// 节点启用新hash并停用原hash
			contractService.enableHash(CnsType.DEFAULT, hash, oldHash);
			// 初始化机构cns 目的是当admin首次部署合约未启用evidenceHash之前，用此私钥占用其配置空间，并且vpc2可以检测出已vpc1已配置
			// 此方法为存写入方法，每次覆盖
			WeIdSdkUtils.getDataBucket(CnsType.ORG_CONFING).put(
					fiscoConfig.getCurrentOrgId(),
					WeIdConstant.CNS_EVIDENCE_ADDRESS + 0, "0x0",
					currentPrivateKey
			);
			//重新加载合约地址
			reloadAddress();
			log.info("[enableHash] enable the hash {} successFully.", hash);
			contractService.createWeIdForCurrentUser(DataFrom.WEB);
			return new ResponseData<>(Boolean.TRUE, ErrorCode.SUCCESS);
		} catch (WeIdBaseException e) {
			log.error("[enableHash] enable the hash error.", e);
			return new ResponseData<>(Boolean.FALSE, e.getErrorCode());
		} catch (Exception e) {
			log.error("[enableHash] enable the hash error.", e);
			return new ResponseData<>(Boolean.FALSE, ErrorCode.UNKNOW_ERROR.getCode(), e.getMessage());
		}
	}

	@Description("此接口用于给命令版本部署后，重载合约地址")
	@GetMapping("/reloadAddress")
	public void reloadAddress() {
		log.info("start reload address.");
		configService.reloadAddress();
	}
}
