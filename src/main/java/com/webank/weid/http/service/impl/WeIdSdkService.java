package com.webank.weid.http.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.jackson.JsonLoader;
import com.webank.weid.constant.CnsType;
import com.webank.weid.constant.ErrorCode;
import com.webank.weid.http.constant.BuildToolsConstant;
import com.webank.weid.http.constant.DataFrom;
import com.webank.weid.http.constant.FileOperator;
import com.webank.weid.http.dto.*;
import com.webank.weid.http.util.ConfigUtils;
import com.webank.weid.http.util.FileUtils;
import com.webank.weid.http.util.WeIdSdkUtils;
import com.webank.weid.protocol.base.*;
import com.webank.weid.protocol.request.CptStringArgs;
import com.webank.weid.protocol.request.CreateWeIdArgs;
import com.webank.weid.protocol.request.RegisterAuthorityIssuerArgs;
import com.webank.weid.protocol.response.CreateWeIdDataResult;
import com.webank.weid.protocol.response.ResponseData;
import com.webank.weid.rpc.AuthorityIssuerService;
import com.webank.weid.rpc.CptService;
import com.webank.weid.rpc.WeIdService;
import com.webank.weid.service.BaseService;
import com.webank.weid.service.fisco.WeServerUtils;
import com.webank.weid.service.impl.AuthorityIssuerServiceImpl;
import com.webank.weid.service.impl.CptServiceImpl;
import com.webank.weid.service.impl.WeIdServiceImpl;
import com.webank.weid.util.DataToolUtils;
import com.webank.weid.util.WeIdUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WeIdSdkService extends BaseService{

	@Autowired
	private ConfigService configService;

	private CptService cptService;
	private AuthorityIssuerService authorityIssuerService;
	private WeIdService weIdService;

	private CptService getCptService() {
		if (cptService == null) {
			cptService = new CptServiceImpl();
		}
		return cptService;
	}

	private WeIdService getWeIdService() {
		if (weIdService == null) {
			weIdService = new WeIdServiceImpl();
		}
		return weIdService;
	}

	private AuthorityIssuerService getAuthorityIssuerService() {
		if (authorityIssuerService == null) {
			authorityIssuerService = new AuthorityIssuerServiceImpl();
		}
		return authorityIssuerService;
	}

	public ResponseData<PageDto<CptInfo>> getCptList(PageDto<CptInfo> pageDto) {
		if ("user".equals(pageDto.getQuery().getCptType())) {
			pageDto.setStartIndex(pageDto.getStartIndex() + BuildToolsConstant.CPTID_LIST.size());
		}

		ResponseData<List<Integer>> response =
				getCptService().getCptIdList(pageDto.getStartIndex(), pageDto.getPageSize());
		List<CptInfo> list = new ArrayList<>();
		if (response.getErrorCode() == ErrorCode.SUCCESS.getCode()) {
			for (Integer cptId : response.getResult()) {
				Cpt cpt = getCptService().queryCpt(cptId).getResult();
				CptInfo cptInfo = new CptInfo();
				cptInfo.setCptId(cptId);
				cptInfo.setCptTitle((String)cpt.getCptJsonSchema().get("title"));
				cptInfo.setWeId(cpt.getCptPublisher());
				cptInfo.setCptDesc((String)cpt.getCptJsonSchema().get("description"));
				cptInfo.setTime(cpt.getCreated());
				if (cptId < 1000) {
					cptInfo.setCptType("sys");
				} else {
					cptInfo.setCptType("user");
				}
				if (StringUtils.isBlank(pageDto.getQuery().getCptType())) {
					list.add(cptInfo);
				} else if (cptInfo.getCptType().equals(pageDto.getQuery().getCptType())) {
					list.add(cptInfo);
				}
			}
		} else {
			log.warn("[getCptList] query getCptList from chain fail: {} - {}.", response.getErrorCode(), response.getErrorMessage());
			return new ResponseData<>(null, response.getErrorCode(), response.getErrorMessage());
		}

		if ("sys".equals(pageDto.getQuery().getCptType())) {
			pageDto.setAllCount(BuildToolsConstant.CPTID_LIST.size());
		} else if ("user".equals(pageDto.getQuery().getCptType())) {
			pageDto.setAllCount(getCptService().getCptCount().getResult() - BuildToolsConstant.CPTID_LIST.size());
		} else {
			pageDto.setAllCount(getCptService().getCptCount().getResult());
		}
		pageDto.setDataList(list);
		return new ResponseData<>(pageDto, ErrorCode.SUCCESS);
	}
	/**
	 * 获取群组列表
	 * @param filterMaster 是否过滤主群组
	 * @return 返回群组列表
	 */
	public List<String> getAllGroup(boolean filterMaster) {
		List<String> list = WeServerUtils.getGroupList();
		if (filterMaster) {
			return list.stream()
					.filter(s -> !s.equals(BaseService.masterGroupId.toString()))
					.collect(Collectors.toList());
		}
		return list;
	}

	public AuthorityIssuer getIssuerByWeId(String weIdAddress) {
		String mainHash = WeIdSdkUtils.getMainHash();
		if (StringUtils.isBlank(mainHash)) {
			return null;
		}
		String weId = WeIdUtils.convertAddressToWeId(weIdAddress);

		log.info("[getIssuerByWeId] begin query issuer. weid = {}", weId);
		ResponseData<AuthorityIssuer> response = getAuthorityIssuerService().queryAuthorityIssuerInfo(weId);
		if (!response.getErrorCode().equals(ErrorCode.SUCCESS.getCode())) {
			log.warn("[getIssuerByWeId] query issuer fail. ErrorCode is:{}, msg :{}",
					response.getErrorCode(),
					response.getErrorMessage());
			return null;
		} else {
			return response.getResult();
		}
	}

	public ResponseData<Boolean> registerCpt(File cptFile, String cptId, DataFrom from) {
		try {
			ResponseData<CptFile> responseData = registerCpt(cptFile, getCurrentWeIdAuth(), cptId, from);
			if (responseData.getErrorCode() != ErrorCode.SUCCESS.getCode()) {
				return new ResponseData<>(Boolean.FALSE, responseData.getErrorCode(), responseData.getErrorMessage());
			}
		} catch (IOException e) {
			return new ResponseData<>(Boolean.FALSE, ErrorCode.UNKNOW_ERROR.getCode(), e.getMessage());
		}

		return new ResponseData<>(Boolean.TRUE, ErrorCode.SUCCESS);
	}

	private WeIdAuthentication getCurrentWeIdAuth() {
		String hash = WeIdSdkUtils.getMainHash();
		WeIdPrivateKey weIdPrivateKey = WeIdSdkUtils.getWeIdPrivateKey(hash);
		WeIdAuthentication callerAuth = new WeIdAuthentication();
		callerAuth.setWeIdPrivateKey(weIdPrivateKey);
		callerAuth.setWeId(WeIdUtils.convertPublicKeyToWeId(
				DataToolUtils.publicKeyFromPrivate(new BigInteger(weIdPrivateKey.getPrivateKey())).toString()));
		callerAuth.setWeIdPublicKeyId(callerAuth.getWeId());
		return callerAuth;
	}

	public ResponseData<CptFile> registerCpt(
			File cptFile,
			WeIdAuthentication callerAuth,
			String cptId,
			DataFrom from) throws IOException {

		String fileName = cptFile.getName();
		log.info("[registerCpt] begin register CPT file: {}", fileName);
		CptFile result = new CptFile();
		result.setCptFileName(fileName);
		if (!fileName.endsWith(".json")) {
			log.error("the file type error. fileName={}", fileName);
			return new ResponseData<>(result, ErrorCode.UNKNOW_ERROR.getCode(), "the file type error");
		}
		JsonNode jsonNode = JsonLoader.fromFile(cptFile);
		String cptJsonSchema = jsonNode.toString();
		CptStringArgs cptStringArgs = new CptStringArgs();
		cptStringArgs.setCptJsonSchema(cptJsonSchema);
		cptStringArgs.setWeIdAuthentication(callerAuth);

		ResponseData<CptBaseInfo> response;
		if (StringUtils.isEmpty(cptId)) {
			response = getCptService().registerCpt(cptStringArgs);
		} else {
			Integer cptId1 = Integer.valueOf(cptId);
			response = getCptService().registerCpt(cptStringArgs, cptId1);
		}
		//System.out.println("[RegisterCpt] result:" + DataToolUtils.serialize(response));
		if (!response.getErrorCode().equals(ErrorCode.SUCCESS.getCode())) {
			log.error("[registerCpt] load config faild. ErrorCode is:{}, msg :{}",
					response.getErrorCode(),
					response.getErrorMessage());
			return new ResponseData<>(result, response.getErrorCode(), response.getErrorMessage());
		}

		log.info("[registerCpt] register CPT file:{} result is success. cpt id = {}", fileName,
				response.getResult().getCptId());

		Integer resultCptId = response.getResult().getCptId();
		String content = new StringBuffer()
				.append(fileName)
				.append("=")
				.append(resultCptId)
				.append("\r\n")
				.toString();
		FileUtils.writeToFile(content, BuildToolsConstant.CPT_RESULT_PATH + "/regist_cpt.out", FileOperator.APPEND);

		log.info("[registerCpt] begin save register info.");
		//开始保存CPT数据
		File cptDir = new File(BuildToolsConstant.CPT_PATH + "/" + WeIdSdkUtils.getMainHash() + "/" + resultCptId);
		cptDir.mkdirs();
		//复制CPT文件
		FileUtils.copy(cptFile, new File(cptDir.getAbsolutePath(), cptFile.getName()));
		//构建cpt数据文件
		String data = DataToolUtils.serialize(
				buildCptInfo(callerAuth.getWeId(), resultCptId, fileName, from, jsonNode));
		File cptInfoFile = new File(cptDir.getAbsoluteFile(), "info");
		FileUtils.writeToFile(data, cptInfoFile.getAbsolutePath(), FileOperator.OVERWRITE);
		//链上查询cpt写链上cpt文件
		ResponseData<String> responseData = queryCptSchema(resultCptId);
		if (responseData.getErrorCode() != ErrorCode.SUCCESS.getCode()) {
			return new ResponseData<>(result, responseData.getErrorCode(), responseData.getErrorMessage());
		}
		String cptSchema = responseData.getResult();
		File file = new File(cptDir.getAbsoluteFile(), getCptFileName(resultCptId));
		FileUtils.writeToFile(cptSchema, file.getAbsolutePath(), FileOperator.OVERWRITE);
//        result.setMessage(BuildToolsConstant.SUCCESS);
		result.setCptId(resultCptId);
		return new ResponseData<>(result, ErrorCode.SUCCESS);
	}

	public ResponseData<String> queryCptSchema(Integer cptId) {
		ResponseData<Cpt> response = getCptService().queryCpt(cptId);
		if (!response.getErrorCode().equals(ErrorCode.SUCCESS.getCode())) {
			log.error("[queryCptInfo] query cpt fail. ErrorCode is:{}, msg :{}",
					response.getErrorCode(),
					response.getErrorMessage());
			return new ResponseData<>(StringUtils.EMPTY, response.getErrorCode(), response.getErrorMessage());
		}

		log.info("[queryCptInfo] query CPT is success. cpt id = {}", response.getResult().getCptId());
		return new ResponseData<>(ConfigUtils.serializeWithPrinter(response.getResult().getCptJsonSchema()),
				ErrorCode.SUCCESS);
	}

	private String getCptFileName(Integer cptId) {
		return "Cpt" + cptId + ".json";
	}

	private CptInfo buildCptInfo(String weId, Integer cptId, String cptJsonName, DataFrom from, JsonNode jsonNode) {
		CptInfo info = new CptInfo();
		info.setHash(WeIdSdkUtils.getMainHash());
		long time = System.currentTimeMillis();
		info.setTime(time);
		info.setCptId(cptId);
		info.setWeId(weId);
		info.setCptJsonName(cptJsonName);
		info.setFrom(from.name());
		info.setCptTitle(jsonNode.get("title").asText());
		info.setCptDesc(jsonNode.get("description").asText());
		return info;
	}

	/**
	 * 检查weid是否存在
	 * @param weId 需要被检查的WeId
	 * @return 返回weId是否存在
	 */
	public boolean checkWeId(String weId) {
		ResponseData<Boolean> response = getWeIdService().isWeIdExist(weId);
		if (!response.getErrorCode().equals(ErrorCode.SUCCESS.getCode())) {
			log.error(
					"[checkWeId] check the WeID faild. error code : {}, error msg :{}",
					response.getErrorCode(),
					response.getErrorMessage());
			return false;
		}
		return response.getResult();
	}

	/**
	 * 部署admin的weid
	 * @param arg 创建weId的参数
	 * @param from 数据来源
	 * @param isAdmin 是否为管理员
	 * @return 返回创建weId结果
	 */
	public ResponseData<String> createWeId(CreateWeIdArgs arg, DataFrom from, boolean isAdmin) {
		ResponseData<String> response = getWeIdService().createWeId(arg);
		if (!response.getErrorCode().equals(ErrorCode.SUCCESS.getCode())) {
			log.error(
					"[CreateWeId] create WeID faild. error code : {}, error msg :{}",
					response.getErrorCode(),
					response.getErrorMessage());
			return response;
		}

		String weId = response.getResult();
		saveWeId(weId, arg.getPublicKey(), arg.getWeIdPrivateKey().getPrivateKey(), from, isAdmin);
		return response;
	}

	private void saveWeId(
			String weId,
			String publicKey,
			String privateKey,
			DataFrom from,
			boolean isAdmin
	) {
		//write weId, publicKey and privateKey to output dir
		File targetDir = getWeidDir(getWeIdAddress(weId));
		if (from == DataFrom.COMMAND) {
			FileUtils.writeToFile(weId, BuildToolsConstant.WEID_FILE); //适配命令输出
		}
		FileUtils.writeToFile(weId, new File(targetDir, BuildToolsConstant.WEID_FILE).getAbsolutePath());
		FileUtils.writeToFile(publicKey, new File(targetDir, BuildToolsConstant.ECDSA_PUB_KEY).getAbsolutePath());
		if (StringUtils.isNotBlank(privateKey)) {
			FileUtils.writeToFile(privateKey, new File(targetDir, BuildToolsConstant.ECDSA_KEY).getAbsolutePath());
		}
		saveWeIdInfo(weId, publicKey, privateKey, from, isAdmin);
	}

	public File getWeidDir(String address) {
		address = FileUtils.getSecurityFileName(address);
		return new File(BuildToolsConstant.WEID_PATH + "/" + WeIdSdkUtils.getMainHash() + "/" + address);
	}

	public ResponseData<String> getWeidDir() {
		String mainHash = WeIdSdkUtils.getMainHash();
		if (StringUtils.isBlank(mainHash)) {
			log.error("mainHash is empty");
			return new ResponseData<>(StringUtils.EMPTY, ErrorCode.BASE_ERROR);
		}

		return new ResponseData<>(new File(BuildToolsConstant.WEID_PATH + "/" + mainHash).getAbsolutePath(),
				ErrorCode.SUCCESS);
	}

	private void saveWeIdInfo(
			String weId,
			String publicKey,
			String privateKey,
			DataFrom from,
			boolean isAdmin
	) {
		log.info("[saveWeIdInfo] begin to save weid info...");
		//创建部署目录
		File targetDir = getWeidDir(getWeIdAddress(weId));
		File saveFile = new File(targetDir.getAbsoluteFile(), "info");
		FileUtils.writeToFile(
				buildInfo(weId, publicKey, privateKey, from, isAdmin),
				saveFile.getAbsolutePath(),
				FileOperator.OVERWRITE);
		log.info("[saveWeIdInfo] save the weid info successfully.");
	}

	private String buildInfo(
			String weId,
			String publicKey,
			String privateKey,
			DataFrom from,
			boolean isAdmin
	) {
		WeIdInfo info = new WeIdInfo();
		info.setId(getWeIdAddress(weId));
		long time = System.currentTimeMillis();
		info.setTime(time);
		info.setEcdsaKey(privateKey);
		info.setEcdsaPubKey(publicKey);
		info.setWeId(weId);
		info.setHash(WeIdSdkUtils.getMainHash());
		info.setFrom(from.name());
		info.setAdmin(isAdmin);
		return DataToolUtils.serialize(info);
	}

	private String getWeIdAddress(String weId) {
		return WeIdUtils.convertWeIdToAddress(weId);
	}

	public WeIdInfo getWeIdInfo(String address) {
		File targetDir = getWeidDir(address);
		File weIdFile = new File(targetDir.getAbsoluteFile(), "info");
		String jsonData = FileUtils.readFile(weIdFile.getAbsolutePath());
		if (StringUtils.isBlank(jsonData)) {
			return null;
		}
		return DataToolUtils.deserialize(jsonData, WeIdInfo.class);
	}

	/**
	 * 注册issuer.
	 * @param weId 被注册成issuer的WeId
	 * @param name 注册issuer名
	 * @param description 注册issuer备注
	 * @return 返回注册结果，true表示成功，false表示失败
	 */
	public ResponseData<Boolean> registerIssuer(String weId, String name, String description) {
		log.info("[registerIssuer] begin register authority issuer..., weId={}, name={}, description={}", weId, name, description);
		RegisterAuthorityIssuerArgs registerAuthorityIssuerArgs = new RegisterAuthorityIssuerArgs();
		AuthorityIssuer authorityIssuer = new AuthorityIssuer();
		authorityIssuer.setName(name);
		authorityIssuer.setWeId(weId);
		authorityIssuer.setAccValue("1");
		authorityIssuer.setCreated(System.currentTimeMillis());
		authorityIssuer.setDescription(description);
		registerAuthorityIssuerArgs.setAuthorityIssuer(authorityIssuer);
		String hash = WeIdSdkUtils.getMainHash();
		registerAuthorityIssuerArgs.setWeIdPrivateKey(WeIdSdkUtils.getWeIdPrivateKey(hash));
		return getAuthorityIssuerService().registerAuthorityIssuer(registerAuthorityIssuerArgs);
	}

	/**
	 * 认证issuer.
	 * @param weId 需要认证的weId
	 * @return 返回认证结果
	 */
	public ResponseData<Boolean> recognizeAuthorityIssuer(String weId) {
		WeIdPrivateKey weIdPrivateKey = WeIdSdkUtils.getWeIdPrivateKey(WeIdSdkUtils.getMainHash());
		return getAuthorityIssuerService().recognizeAuthorityIssuer(weId, weIdPrivateKey);
	}

	/**
	 * 撤销认证issuer.
	 * @param weId 需要认证的weId
	 * @return 返回认证结果
	 */
	public ResponseData<Boolean> deRecognizeAuthorityIssuer(String weId) {
		WeIdPrivateKey weIdPrivateKey = WeIdSdkUtils.getWeIdPrivateKey(WeIdSdkUtils.getMainHash());
		return getAuthorityIssuerService().deRecognizeAuthorityIssuer(weId, weIdPrivateKey);
	}

	public ResponseData<PageDto<Issuer>> getIssuerList(PageDto<Issuer> pageDto) {
		String hash = WeIdSdkUtils.getMainHash();
		ResponseData<List<AuthorityIssuer>> response =
				getAuthorityIssuerService().getAllAuthorityIssuerList(pageDto.getStartIndex(), pageDto.getPageSize());
		List<Issuer> list = new ArrayList<>();
		if (response.getErrorCode() == ErrorCode.SUCCESS.getCode()) {
			if (CollectionUtils.isNotEmpty(response.getResult())) {
				response.getResult().forEach(authorityIssuer -> {
					Issuer issuer = new Issuer();
					issuer.setWeId(authorityIssuer.getWeId());
					issuer.setName(authorityIssuer.getName());
					issuer.setCreateTime(String.valueOf(authorityIssuer.getCreated()));
					issuer.setHash(hash);
					issuer.setDescription(authorityIssuer.getDescription());
					issuer.setRecognized(authorityIssuer.isRecognized());
					list.add(issuer);
				});
			}
		} else {
			log.warn("[getIssuerList] query issuerList from chain fail: {} - {}.", response.getErrorCode(), response.getErrorMessage());
			return new ResponseData<>(null, response.getErrorCode(),  response.getErrorMessage());
		}
		Integer allCount = getAuthorityIssuerService().getIssuerCount().getResult();
		pageDto.setAllCount(allCount);
		pageDto.setDataList(list);
		return new ResponseData<>(pageDto, ErrorCode.SUCCESS);
	}

	public ResponseData<PageDto<WeIdInfo>> getWeIdList(
			PageDto<WeIdInfo> pageDto,
			Integer blockNumber,
			Integer pageSize,
			Integer indexInBlock,
			boolean direction
	) {
		ResponseData<List<WeIdPojo>> response = getWeIdService().getWeIdList(blockNumber, pageSize, indexInBlock, direction);
		if (response.getErrorCode() != ErrorCode.SUCCESS.getCode()) {
			log.error("[getWeIdList] get weIdList has error, {} - {}", response.getErrorCode(), response.getErrorMessage());
			return new ResponseData<>(pageDto, response.getErrorCode(), response.getErrorMessage());
		}
		List<WeIdPojo> list = response.getResult();
		pageDto.setAllCount(getWeIdService().getWeIdCount().getResult());
		List<WeIdInfo> rList = new ArrayList<>();
		if (list.size() > 0) {
			String mainHash = WeIdSdkUtils.getMainHash();
			for (WeIdPojo weIdPojo : list) {
				WeIdInfo weInfo = getWeIdInfo(this.getWeIdAddress(weIdPojo.getId()));
				if(weInfo == null) {
					weInfo = new WeIdInfo();
				}
				weInfo.setWeIdPojo(weIdPojo);
				weInfo.setWeId(weIdPojo.getId());
				AuthorityIssuer issuer = getAuthorityIssuerService().queryAuthorityIssuerInfo(weIdPojo.getId()).getResult();
				weInfo.setIssuer(issuer != null);
				weInfo.setHash(mainHash);
				rList.add(weInfo);
			}
		}
		pageDto.setDataList(rList);
		return new ResponseData<>(pageDto, ErrorCode.SUCCESS);
	}

	public List<IssuerType> getIssuerTypeList() {
		String currentHash = WeIdSdkUtils.getMainHash();
		List<IssuerType> list = new ArrayList<>();
		if (StringUtils.isBlank(currentHash)) {
			return list;
		}
		File targetDir = new File(BuildToolsConstant.ISSUER_TYPE_PATH + "/" + currentHash);
		if (!targetDir.exists()) {
			return list;
		}
		for (File file : Objects.requireNonNull(targetDir.listFiles())) {
			//根据weid判断本地是否存在
			String jsonData = FileUtils.readFile(file.getAbsolutePath());
			IssuerType info = DataToolUtils.deserialize(jsonData, IssuerType.class);
			list.add(info);
		}
		Collections.sort(list);
		return list;
	}

	public ResponseData<String> createWeId(DataFrom from) {
		ResponseData<CreateWeIdDataResult> response = getWeIdService().createWeId();
		if (!response.getErrorCode().equals(ErrorCode.SUCCESS.getCode())) {
			log.error(
					"[CreateWeId] create WeID faild. error code : {}, error msg :{}",
					response.getErrorCode(),
					response.getErrorMessage());
			return new ResponseData<>(StringUtils.EMPTY, response.getErrorCode(), response.getErrorMessage());
		}
		CreateWeIdDataResult result = response.getResult();
		String weId = result.getWeId();
		//System.out.println("weid is ------> " + weId);
		String publicKey = result.getUserWeIdPublicKey().getPublicKey();
		String privateKey = result.getUserWeIdPrivateKey().getPrivateKey();
		saveWeId(weId, publicKey, privateKey, from, false);
		return new ResponseData<>(weId, ErrorCode.SUCCESS);
	}

	// 根据私钥创建weId
	public ResponseData<String> createWeIdByPrivateKey(HttpServletRequest request, DataFrom from) {
		String privateKey;
		try {
			MultipartFile file = ((MultipartHttpServletRequest) request).getFile("privateKey");
			privateKey = new String(file.getBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.error("get private key failed.", e);
			return new ResponseData<>(StringUtils.EMPTY, ErrorCode.BASE_ERROR);
		}

		CreateWeIdArgs arg = new CreateWeIdArgs();
		WeIdPrivateKey weIdPrivateKey = new WeIdPrivateKey();
		weIdPrivateKey.setPrivateKey(privateKey);
		arg.setWeIdPrivateKey(weIdPrivateKey);
		arg.setPublicKey(DataToolUtils.publicKeyFromPrivate(new BigInteger(privateKey)).toString());
		return this.createWeId(arg, from, false);
	}

	// 根据公钥代理创建weId(只有主群组管理员才可以调用)
	public ResponseData<String> createWeIdByPublicKey(HttpServletRequest request, DataFrom from) {
		String publicKey;
		try {
			MultipartFile file = ((MultipartHttpServletRequest) request).getFile("publicKey");
			publicKey = new String(file.getBytes(),StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.error("get public key failed.", e);
			return new ResponseData<>(StringUtils.EMPTY, ErrorCode.BASE_ERROR);
		}

		// 判断当前使用的cns是否为当前admin部署的
		// 获取当前配置的hash值
		String hash = WeIdSdkUtils.getMainHash();
		// 获取所有的主合约cns值，从而获取当前cns的部署者
		List<HashContract> result = WeIdSdkUtils.getDataBucket(CnsType.DEFAULT).getAllBucket().getResult();
		// 当前hash的所有者
		String currentHashOwner =  null;
		for (HashContract hashContract : result) {
			if (hashContract.getHash().equals(hash)) {
				currentHashOwner = hashContract.getOwner();
				break;
			}
		}
		// 转换成weid
		String owner = WeIdUtils.convertAddressToWeId(currentHashOwner);
		WeIdAuthentication currentWeIdAuth = getCurrentWeIdAuth();
		// 如果当前weId地址跟 owner地址一致说明是主群组管理员
		if (currentWeIdAuth.getWeId().equals(owner)) {
			WeIdPublicKey weidPublicKey = new WeIdPublicKey();
			weidPublicKey.setPublicKey(publicKey);
			ResponseData<String> response = getWeIdService().delegateCreateWeId(weidPublicKey, currentWeIdAuth);
			if (!response.getErrorCode().equals(ErrorCode.SUCCESS.getCode())) {
				log.error(
						"[CreateWeId] create WeID faild. error code : {}, error msg :{}",
						response.getErrorCode(),
						response.getErrorMessage());
				return new ResponseData<>(StringUtils.EMPTY, response.getErrorCode(), response.getErrorMessage());
			}
			String weId = response.getResult();
			saveWeId(weId, publicKey, null, from, false);
			return new ResponseData<>(weId, ErrorCode.SUCCESS);
		}
		return new ResponseData<>(StringUtils.EMPTY, ErrorCode.BASE_ERROR.getCode(),
				"create fail: no permission.");
	}

	public ResponseData<List<Map<String, String>>> getGroupMapping() {
		Map<String, List<String>> groupMapping = WeServerUtils.getGroupMapping();
		Set<Map.Entry<String, List<String>>> entrySet = groupMapping.entrySet();
		List<Map<String, String>> list = new ArrayList<>();
		String masterGroupId = configService.loadConfig().get("group_id");
		for (Map.Entry<String, List<String>> entry : entrySet) {
			Map<String, String> info = new HashMap<>();
			info.put("groupId", entry.getKey());
			info.put("nodes", entry.getValue().toString());
			info.put("type", "子群组");
			if (masterGroupId.equals(entry.getKey())) {
				info.put("type", "主群组");
				list.add(0, info);
			} else {
				list.add(info);
			}
		}
		return new ResponseData<>(list, ErrorCode.SUCCESS);
	}


}
