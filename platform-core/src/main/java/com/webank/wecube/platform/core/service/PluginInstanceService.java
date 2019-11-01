package com.webank.wecube.platform.core.service;

import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Lists;
import com.webank.wecube.platform.core.support.S3Client;
import com.webank.wecube.platform.core.utils.EncryptionUtils;
import com.webank.wecube.platform.core.utils.JsonUtils;
import com.webank.wecube.platform.core.utils.StringUtils;
import com.webank.wecube.platform.core.utils.SystemUtils;
import com.webank.wecube.platform.core.utils.ZipFileUtils;

import javassist.expr.NewArray;
import net.bytebuddy.asm.Advice.Return;

import com.webank.wecube.platform.core.service.ScpService;
import com.webank.wecube.platform.core.service.CommandService;
import com.webank.wecube.platform.core.commons.ApplicationProperties.ResourceProperties;
import com.webank.wecube.platform.core.commons.ApplicationProperties;
import com.webank.wecube.platform.core.commons.ApplicationProperties.PluginProperties;
import com.webank.wecube.platform.core.commons.WecubeCoreException;
import com.webank.wecube.platform.core.domain.ResourceItem;
import com.webank.wecube.platform.core.domain.ResourceServer;
import com.webank.wecube.platform.core.domain.plugin.PluginInstance;
import com.webank.wecube.platform.core.domain.plugin.PluginMysqlInstance;
import com.webank.wecube.platform.core.domain.plugin.PluginPackage;
import com.webank.wecube.platform.core.domain.plugin.PluginPackageRuntimeResourcesDocker;
import com.webank.wecube.platform.core.domain.plugin.PluginPackageRuntimeResourcesMysql;
import com.webank.wecube.platform.core.domain.plugin.PluginPackageRuntimeResourcesS3;
import com.webank.wecube.platform.core.dto.CreateInstanceDto;
import com.webank.wecube.platform.core.dto.QueryRequest;
import com.webank.wecube.platform.core.dto.ResourceItemDto;
import com.webank.wecube.platform.core.dto.ResourceServerDto;
import com.webank.wecube.platform.core.jpa.PluginConfigRepository;
import com.webank.wecube.platform.core.jpa.PluginInstanceRepository;
import com.webank.wecube.platform.core.jpa.PluginMysqlInstanceRepository;
import com.webank.wecube.platform.core.jpa.PluginPackageRepository;
import com.webank.wecube.platform.core.jpa.ResourceItemRepository;
import com.webank.wecube.platform.core.jpa.ResourceServerRepository;
import com.webank.wecube.platform.core.service.resource.ResourceItemType;
import com.webank.wecube.platform.core.service.resource.ResourceManagementService;
import com.webank.wecube.platform.core.service.resource.ResourceServerType;

import static com.google.common.collect.Lists.newArrayList;
import static org.apache.commons.lang3.StringUtils.trim;

@Service
@Transactional
public class PluginInstanceService {
    private static final Logger logger = LoggerFactory.getLogger(PluginPackageDataModelServiceImpl.class);

    @Autowired
    private PluginProperties pluginProperties;

    @Autowired
    PluginInstanceRepository pluginInstanceRepository;
    @Autowired
    PluginPackageRepository pluginPackageRepository;
    @Autowired
    PluginConfigRepository pluginConfigRepository;
    @Autowired
    ResourceServerRepository resourceServerRepository;
    @Autowired
    PluginMysqlInstanceRepository pluginMysqlInstanceRepository;

    @Autowired
    private S3Client s3Client;
    @Autowired
    private ScpService scpService;
    @Autowired
    private CommandService commandService;
    @Autowired
    private ResourceProperties resourceProperties;

    @Autowired
    private ResourceManagementService resourceManagementService;
    @Autowired
    private ResourceItemRepository resourceItemRepository;

    private static final int PLUGIN_DEFAULT_START_PORT = 20000;
    private static final int PLUGIN_DEFAULT_END_PORT = 30000;
    private static final String SPACE = " ";

    public List<String> getAvailableContainerHosts() {
        QueryRequest queryRequest = QueryRequest.defaultQueryObject("type", ResourceServerType.DOCKER);
        List<String> hostList = new ArrayList<String>();
        resourceManagementService.retrieveServers(queryRequest).getContents().forEach(rs -> {
            hostList.add(rs.getHost());
        });
        return hostList;
    }

    public Integer getAvailablePortByHostIp(String hostIp) {
        if (!(StringUtils.isValidIp(hostIp))) {
            throw new RuntimeException("Invalid host ip");
        }
        ResourceServer resourceServer = resourceServerRepository.findByHost(hostIp).get(0);
        if (null == resourceServer)
            throw new WecubeCoreException(String.format("Host IP [%s] is not found", hostIp));
        QueryRequest queryRequest = QueryRequest.defaultQueryObject("type", ResourceItemType.DOCKER_CONTAINER)
                .addEqualsFilter("resourceServerId", resourceServer.getId());

        List<Integer> hasUsedPorts = Lists.newArrayList();
        resourceManagementService.retrieveItems(queryRequest).getContents().forEach(rs -> {
            Arrays.asList(rs.getAdditionalPropertiesMap().get("portBindings").split(",")).forEach(port -> {
                String[] portArray = port.split(":");
                hasUsedPorts.add(Integer.valueOf(portArray[0]));
            });
        });
        if (hasUsedPorts.size() == 0) {
            return PLUGIN_DEFAULT_START_PORT;
        }

        for (int i = PLUGIN_DEFAULT_START_PORT; i < PLUGIN_DEFAULT_END_PORT; i++) {
            if (!hasUsedPorts.contains(i)) {
                return i;
            }
        }
        throw new WecubeCoreException("There is no available ports in specified host");
    }

    public List<PluginInstance> getAllInstances() {
        return Lists.newArrayList(pluginInstanceRepository.findAll());
    }

    public List<PluginInstance> getAvailableInstancesByPackageId(int packageId) {
        return pluginInstanceRepository.findByStatusAndPackageId(PluginInstance.STATUS_RUNNING, packageId);
    }

    public List<PluginInstance> getRunningPluginInstances(String pluginName) {
        Optional<PluginPackage> pkg = pluginPackageRepository.findLatestVersionByName(pluginName);
        if (!pkg.isPresent()) {
            throw new WecubeCoreException(String.format("Plugin pacakge [%s] not found.", pluginName));
        }

        List<PluginInstance> instances = pluginInstanceRepository
                .findByStatusAndPackageId(PluginInstance.STATUS_RUNNING, pkg.get().getId());
        if (instances == null || instances.size() == 0) {
            throw new WecubeCoreException(String.format("No instance for plugin [%s] is available.", pluginName));
        }
        return instances;
    }

    private boolean isContainerHostValid(String hostIp) {
        if (StringUtils.isValidIp(hostIp) && isHostIpAvailable(hostIp)) {
            return true;
        }
        return false;
    }

    private boolean isPortValid(String hostIp, Integer port) {
        List<PluginInstance> pluginInstances = pluginInstanceRepository.findByHostAndPort(hostIp, port);
        if (pluginInstances.size() == 0 || null == pluginInstances) {
            return true;
        }
        return false;
    }

    private String genRandomPassword() {
        String md5String = DigestUtils.md5Hex(String.valueOf(System.currentTimeMillis()));
        return md5String.length() > 16 ? md5String.substring(0, 16) : md5String;
    }

    private void validateLauchPluginInstanceParameters(PluginPackage pluginPackage, String hostIp, Integer port)
            throws Exception {
        if (!isContainerHostValid(hostIp))
            throw new WecubeCoreException("Unavailable container host ip");

        if (!isPortValid(hostIp, port))
            throw new IllegalArgumentException(String.format(
                    "The port[%d] of host[%s] is already in used, please try to reassignment port", port, hostIp));

        if (pluginPackage.getStatus().equals(PluginPackage.Status.DECOMMISSIONED)
                || pluginPackage.getStatus().equals(PluginPackage.Status.UNREGISTERED))
            throw new WecubeCoreException("'DECOMMISSIONED' or 'UNREGISTERED' state can not launch plugin instance ");
    }

    public void launchPluginInstance(Integer packageId, String hostIp, Integer port) throws Exception {
        Optional<PluginPackage> pluginPackageResult = pluginPackageRepository.findById(packageId);
        if (!pluginPackageResult.isPresent())
            throw new WecubeCoreException("Plugin package id does not exist, id = " + packageId);
        PluginPackage pluginPackage = pluginPackageResult.get();

        validateLauchPluginInstanceParameters(pluginPackage, hostIp, port);

        PluginInstance instance = new PluginInstance();
        instance.setPluginPackage(pluginPackage);

        InitPaasResourceReturn initPaasResourceReturn = initPaasResource(instance);

        instance.setPluginMysqlInstanceResourceId(initPaasResourceReturn.getMysqlInstanceResourceId());
        instance.setS3BucketResourceId(initPaasResourceReturn.getS3BucketResourceId());

        DatabaseInfo dbInfo = initPaasResourceReturn.getDbInfo();

        // 3. create docker instance
        PluginPackageRuntimeResourcesDocker dockerInfo = pluginPackage.getPluginPackageRuntimeResourcesDocker()
                .iterator().next();
        CreateInstanceDto createContainerParameters = new CreateInstanceDto(dockerInfo.getImageName(),
                dockerInfo.getContainerName(),
                dockerInfo.getPortBindings().replace("{{host_port}}", String.valueOf(port)),
                dockerInfo.getVolumeBindings(),
                dockerInfo.getEnvVariables().replace("{{data_source_url}}", dbInfo.getConnectString())
                        .replace("{{db_user}}", dbInfo.getUser()).replace("{{db_password}}", dbInfo.getPassword()));
        try {
            ResourceItemDto dockerResourceDto = createPluginDockerInstance(pluginPackage, hostIp,
                    createContainerParameters);

            instance.setInstanceName(dockerInfo.getContainerName());
            instance.setDockerInstanceResourceId(dockerResourceDto.getId());
            instance.setHost(hostIp);
            instance.setPort(port);
        } catch (Exception e) {
            logger.error("Creating docker container instance meet error: ", e.getMessage());
            e.printStackTrace();
        }

        // 4. insert to DB
        instance.setStatus(PluginInstance.STATUS_RUNNING);
        pluginInstanceRepository.save(instance);

        // TODO - 6. notify gateway
    }

    private InitPaasResourceReturn initPaasResource(PluginInstance instance) {
        InitPaasResourceReturn initPaasResourceReturn = initMysqlDatabaseSchema(instance, new InitPaasResourceReturn());

        Integer s3BucketResourceId = initS3BucketResource(instance, initPaasResourceReturn);
        if (s3BucketResourceId != null)
            initPaasResourceReturn.setS3BucketResourceId(s3BucketResourceId);

        return initPaasResourceReturn;
    }

    private InitPaasResourceReturn initMysqlDatabaseSchema(PluginInstance instance,
            InitPaasResourceReturn initPaasResourceReturn) {
        PluginPackage pluginPackage = instance.getPluginPackage();
        if (pluginMysqlInstanceRepository.findByPluginPackageIdAndStatus(pluginPackage.getId(), "active").size() == 0) {
            Set<PluginPackageRuntimeResourcesMysql> mysqlSet = pluginPackage.getPluginPackageRuntimeResourcesMysql();
            if (mysqlSet.size() != 0) {
                PluginMysqlInstance mysqlInstance = createPluginMysqlDatabase(mysqlSet.iterator().next());
                initPaasResourceReturn.setMysqlInstanceResourceId(mysqlInstance.getId());

                ResourceServer dbServer = resourceItemRepository.findById(mysqlInstance.getResourceItemId()).get()
                        .getResourceServer();
                initPaasResourceReturn.setDbInfo(new DatabaseInfo(
                        String.format("jdbc:mysql://%s:%s/%s?characterEncoding=utf8&serverTimezone=UTC",
                                dbServer.getHost(), dbServer.getPort(), mysqlInstance.getSchemaName()),
                        mysqlInstance.getUsername(), mysqlInstance.getPassword()));

                // execute init.sql
                initMysqlDatabaseTables(dbServer, mysqlInstance, pluginPackage);
                return initPaasResourceReturn;
            }
        }
        return new InitPaasResourceReturn();
    }

    private void initMysqlDatabaseTables(ResourceServer dbServer, PluginMysqlInstance mysqlInstance,
            PluginPackage pluginPackage) {

        String tmpFolderName = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
        String initSqlPath = SystemUtils.getTempFolderPath() + tmpFolderName + "/" + pluginProperties.getInitDbSql();

        String s3KeyName = pluginPackage.getName() + File.separator + pluginPackage.getVersion() + File.separator
                + pluginProperties.getInitDbSql();
        logger.info("Download init.sql from S3: {}", s3KeyName);

        s3Client.downFile(pluginProperties.getPluginPackageBucketName(), s3KeyName, initSqlPath);

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://" + dbServer.getHost() + ":" + dbServer.getPort() + "/" + mysqlInstance.getSchemaName()
                        + "?characterEncoding=utf8&serverTimezone=UTC",
                mysqlInstance.getUsername(), EncryptionUtils.decryptWithAes(mysqlInstance.getPassword(),
                        resourceProperties.getPasswordEncryptionSeed(), mysqlInstance.getSchemaName()));
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");

        List<Resource> scipts = newArrayList(new ClassPathResource(initSqlPath));
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.setIgnoreFailedDrops(false);
        populator.setSeparator(";");
        scipts.forEach(populator::addScript);
        try {
            populator.execute(dataSource);
        } catch (Exception e) {
            String errorMessage = String.format("Failed to execute init.sql for schema[%s]",
                    mysqlInstance.getSchemaName());
            logger.error(errorMessage);
            throw new WecubeCoreException(errorMessage, e);
        }
        logger.info(String.format("Init database[%s] tables has done..", mysqlInstance.getSchemaName()));
    }

    private Integer initS3BucketResource(PluginInstance instance, InitPaasResourceReturn initPaasResourceReturn) {
        Set<PluginPackageRuntimeResourcesS3> s3Set = instance.getPluginPackage().getPluginPackageRuntimeResourcesS3();
        if (s3Set.size() != 0) {
            return createPluginS3Bucket(s3Set.iterator().next());
        }
        return null;
    }

    public PluginMysqlInstance createPluginMysqlDatabase(PluginPackageRuntimeResourcesMysql mysqlInfo) {
        QueryRequest queryRequest = QueryRequest.defaultQueryObject("type", ResourceServerType.MYSQL);
        ResourceServerDto mysqlServer = resourceManagementService.retrieveServers(queryRequest).getContents().get(0);

        String dbPassword = genRandomPassword();
        String dbUser = mysqlInfo.getSchemaName();

        ResourceItemDto createMysqlDto = new ResourceItemDto(mysqlInfo.getSchemaName(),
                ResourceItemType.MYSQL_DATABASE.getCode(),
                buildAdditionalPropertiesForMysqlDatabase(dbUser.length() > 16 ? dbUser.substring(0, 16) : dbUser,
                        dbPassword),
                mysqlServer.getId(), String.format("Create MySQL database for plugin[%s]", mysqlInfo.getSchemaName()));
        mysqlServer.setResourceItemDtos(null);
        createMysqlDto.setResourceServer(mysqlServer);
        logger.info("Mysql Database schema creating...");
        if (logger.isDebugEnabled())
            logger.info("Request parameters= " + createMysqlDto);

        List<ResourceItemDto> result = resourceManagementService.createItems(Lists.newArrayList(createMysqlDto));
        PluginMysqlInstance mysqlInstance = new PluginMysqlInstance(mysqlInfo.getSchemaName(), result.get(0).getId(),
                mysqlInfo.getSchemaName(), dbPassword, "active", mysqlInfo.getPluginPackage());
        pluginMysqlInstanceRepository.saveAndFlush(mysqlInstance);

        logger.info("Mysql Database schema creation has done...");
        return mysqlInstance;
    }

    private Integer createPluginS3Bucket(PluginPackageRuntimeResourcesS3 s3Info) {
        QueryRequest queryRequest = QueryRequest.defaultQueryObject("type", ResourceServerType.S3);
        ResourceServerDto s3Server = resourceManagementService.retrieveServers(queryRequest).getContents().get(0);

        ResourceItemDto createS3BucketDto = new ResourceItemDto(s3Info.getBucketName(),
                ResourceItemType.S3_BUCKET.getCode(), null, s3Server.getId(),
                String.format("Create S3 bucket for plugin[%s]", s3Info.getBucketName()));
        createS3BucketDto.setResourceServer(s3Server);
        logger.info("S3 bucket creating...");
        if (logger.isDebugEnabled())
            logger.info("Request parameters= " + createS3BucketDto);

        List<ResourceItemDto> result = resourceManagementService.createItems(Lists.newArrayList(createS3BucketDto));

        logger.info("S3 bucket creation has done...");
        return result.get(0).getId();
    }

    private ResourceItemDto createPluginDockerInstance(PluginPackage pluginPackage, String hostIp,
            CreateInstanceDto createContainerParameters) throws Exception {
        ResourceServer hostInfo = resourceServerRepository.findByHost(hostIp).get(0);

        // download package from MinIO
        String tmpFolderName = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
        String tmpFilePath = SystemUtils.getTempFolderPath() + tmpFolderName + "/" + pluginProperties.getImageFile();

        String s3KeyName = pluginPackage.getName() + File.separator + pluginPackage.getVersion() + File.separator
                + pluginProperties.getImageFile();
        logger.info("Download plugin package from S3: {}", s3KeyName);

        s3Client.downFile(pluginProperties.getPluginPackageBucketName(), s3KeyName, tmpFilePath);

        logger.info("scp from local:{} to remote: {}", tmpFilePath, pluginProperties.getPluginDeployPath());
        try {
            scpService.put(hostIp, Integer.valueOf(hostInfo.getPort()), hostInfo.getLoginUsername(),
                    EncryptionUtils.decryptWithAes(hostInfo.getLoginPassword(),
                            resourceProperties.getPasswordEncryptionSeed(), hostInfo.getName()),
                    tmpFilePath, pluginProperties.getPluginDeployPath());
        } catch (Exception e) {
            throw new WecubeCoreException("Put file to remote host meet error: " + e.getMessage());
        }

        // load image at remote host
        String loadCmd = "docker load -i " + pluginProperties.getPluginDeployPath().trim() + File.separator
                + pluginProperties.getImageFile();
        logger.info("Run docker load command: " + loadCmd);
        try {
            commandService.runAtRemote(hostIp, hostInfo.getLoginUsername(),
                    EncryptionUtils.decryptWithAes(hostInfo.getLoginPassword(),
                            resourceProperties.getPasswordEncryptionSeed(), hostInfo.getName()),
                    Integer.valueOf(hostInfo.getPort()), loadCmd);
        } catch (Exception e) {
            logger.error("Run command [{}] meet error: {}", loadCmd, e.getMessage());
            throw new WecubeCoreException(String.format("Run remote command meet error: %s", e.getMessage()));
        }

        ResourceItemDto createDockerInstanceDto = new ResourceItemDto(createContainerParameters.getContainerName(),
                ResourceItemType.DOCKER_CONTAINER.getCode(),
                buildAdditionalPropertiesForDocker(createContainerParameters), hostInfo.getId(),
                String.format("Create docker instance for plugin[%s]", pluginPackage.getName()));
        logger.info("Container creating...");
        if (logger.isDebugEnabled())
            logger.info("Request parameters= " + createDockerInstanceDto.toString());

        List<ResourceItemDto> result = resourceManagementService
                .createItems(Lists.newArrayList(createDockerInstanceDto));

        logger.info("Container creation has done...");
        return result.get(0);
    }

    public void removePluginInstanceById(Integer instanceId) throws Exception {
        Optional<PluginInstance> instance = pluginInstanceRepository.findById(instanceId);
        ResourceItemDto createDockerInstanceDto = new ResourceItemDto();
        createDockerInstanceDto.setName(instance.get().getInstanceName());
        logger.info("createDockerInstanceDto = " + createDockerInstanceDto.toString());
        resourceManagementService.deleteItems(Lists.newArrayList(createDockerInstanceDto));
    }

    private boolean isHostIpAvailable(String hostIp) {
        if (getAvailableContainerHosts().contains(hostIp))
            return true;
        return false;
    }

    private String buildAdditionalPropertiesForMysqlDatabase(String username, String password) {
        HashMap<String, String> additionalProperties = new HashMap<String, String>();
        additionalProperties.put("username", username);
        additionalProperties.put("password", password);
        return JsonUtils.toJsonString(additionalProperties);
    }

    private String buildAdditionalPropertiesForDocker(CreateInstanceDto createContainerParameters) {
        HashMap<String, String> additionalProperties = new HashMap<String, String>();
        additionalProperties.put("imageName", createContainerParameters.getImageName());
        additionalProperties.put("portBindings", createContainerParameters.getPortBindingParameters());
        additionalProperties.put("volumeBindings", createContainerParameters.getVolumeBindingParameters());
        additionalProperties.put("envVariables", createContainerParameters.getEnvVariableParameters());

        return JsonUtils.toJsonString(additionalProperties);
    }

    public String getInstanceAddress(PluginInstance instance) {
        return trim(instance.getHost()) + ":" + trim(instance.getPort().toString());
    }

    private class InitPaasResourceReturn {
        DatabaseInfo dbInfo;
        Integer mysqlInstanceResourceId;
        Integer s3BucketResourceId;

        public DatabaseInfo getDbInfo() {
            return dbInfo;
        }

        public void setDbInfo(DatabaseInfo dbInfo) {
            this.dbInfo = dbInfo;
        }

        public Integer getMysqlInstanceResourceId() {
            return mysqlInstanceResourceId;
        }

        public void setMysqlInstanceResourceId(Integer mysqlInstanceResourceId) {
            this.mysqlInstanceResourceId = mysqlInstanceResourceId;
        }

        public Integer getS3BucketResourceId() {
            return s3BucketResourceId;
        }

        public void setS3BucketResourceId(Integer s3BucketResourceId) {
            this.s3BucketResourceId = s3BucketResourceId;
        }
    }

    private class DatabaseInfo {
        String connectString;
        String user;
        String password;

        private DatabaseInfo(String connectString, String user, String password) {
            this.connectString = connectString;
            this.user = user;
            this.password = password;
        }

        public DatabaseInfo() {
        }

        public String getConnectString() {
            return connectString;
        }

        public void setConnectString(String connectString) {
            this.connectString = connectString;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

}
