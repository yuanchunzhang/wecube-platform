package com.webank.wecube.platform.core.service.resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.webank.wecube.platform.core.commons.ApplicationProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import com.google.common.collect.Lists;
import com.webank.wecube.platform.core.commons.WecubeCoreException;
import com.webank.wecube.platform.core.domain.ResourceItem;
import com.webank.wecube.platform.core.utils.JsonUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DockerContainerManagementService implements ResourceItemService, ResourceItemOperationService {
    @Autowired
    private ApplicationProperties.DockerRemoteProperties dockerRemoteProperties;

    public DockerClient newDockerClient(String host) {
        String url = String.format("tcp://%s:%d", host,dockerRemoteProperties.getPort());
        if(dockerRemoteProperties.getEnableTls() == true){
            DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost(url)
                    .withDockerTlsVerify(true)
                    .withDockerCertPath(dockerRemoteProperties.getCertPath())
                    .build();
            return DockerClientBuilder.getInstance(config).build();
        }else{
            return DockerClientBuilder.getInstance(url).build();
        }
    }

    public List<Container> listRunningContainers(DockerClient client) {
        return client.listContainersCmd().exec();
    }

    @Override
    public ResourceItem createItem(ResourceItem item) {
        Map<String, String> additionalProperties = item.getAdditionalPropertiesMap();
        DockerClient dockerClient = newDockerClient(item.getResourceServer().getHost());

        String containerName = item.getName();
        String imageName = additionalProperties.get("imageName");
        String portBindingsString = additionalProperties.get("portBindings");
        String volumeBindingsString = additionalProperties.get("volumeBindings");
        String envVariablesString = additionalProperties.get("envVariables");

        List<String> portBindings = (StringUtils.isBlank(portBindingsString) ? Lists.newArrayList()
                : Arrays.asList(portBindingsString.split(",")));
        List<String> volumeBindings = (StringUtils.isBlank(volumeBindingsString) ? Lists.newArrayList()
                : Arrays.asList(volumeBindingsString.split(",")));
        List<String> envVariables = (StringUtils.isBlank(envVariablesString) ? Lists.newArrayList()
                : Arrays.asList(envVariablesString.split("\\\\,")));

        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true)
                .withFilter("name", Arrays.asList(containerName)).exec();
        if (!containers.isEmpty()) {
            throw new WecubeCoreException(
                    String.format("Failed to create the container with name [%s] : Already exists.", containerName));
        }

        List<ExposedPort> exposedPorts = Lists.newArrayList();

        boolean hasPortBindings = false;
        boolean hasVolumeBindings = false;

        Ports portMappings = new Ports();
        if (portBindings.size() != 0) {
            for (String port : portBindings) {
                String[] portArray = port.split(":");
                ExposedPort exposedPort = ExposedPort.tcp(Integer.valueOf(portArray[1]));
                exposedPorts.add(exposedPort);
                portMappings.bind(exposedPort, Ports.Binding.bindPort(Integer.valueOf(portArray[0])));
                hasPortBindings = true;
            }
        }

        List<Bind> volumeMappings = new ArrayList<>();
        List<Volume> containerVolumes = new ArrayList<>();
        if (volumeBindings.size() != 0) {
            for (String volume : volumeBindings) {
                String[] volumeArray = volume.split(":");
                if (volumeArray.length >= 2) {
                    Volume containerVolume = new Volume(volumeArray[1]);
                    containerVolumes.add(containerVolume);
                    Bind bind = new Bind(volumeArray[0], containerVolume, AccessMode.rw);
                    volumeMappings.add(bind);
                    hasVolumeBindings = true;
                }
            }
        }

        HostConfig hostConfig = new HostConfig();
        if (hasPortBindings)
            hostConfig.withPortBindings(portMappings);
        if (hasVolumeBindings)
            hostConfig.withBinds(volumeMappings);

        CreateContainerCmd createContainerCmd = dockerClient.createContainerCmd(imageName).withName(containerName)
                .withVolumes(containerVolumes).withExposedPorts(exposedPorts).withHostConfig(hostConfig);

        List<String> envList = new ArrayList<String>();
        for (String env : envVariables) {
            String[] envArray = env.split("=");
            if (envArray.length == 2 && !envArray[1].trim().isEmpty()) {
                envList.add(envArray[0].trim() + "=" + envArray[1].trim());
            }
        }

        if (envList.size() != 0) {
            createContainerCmd = createContainerCmd.withEnv(envList);
        }
        log.info("createContainerCmd: " + createContainerCmd.toString());
        String containerId = createContainerCmd.exec().getId();
        dockerClient.startContainerCmd(containerId).exec();
        additionalProperties.put("containerId", containerId);
        item.setAdditionalProperties(JsonUtils.toJsonString(additionalProperties));
        return item;
    }

    @Override
    public void deleteItem(ResourceItem item) {
        String containerName = item.getName();
        DockerClient dockerClient = newDockerClient(item.getResourceServer().getHost());

        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true)
                .withFilter("name", Arrays.asList(containerName)).exec();
        if (containers.isEmpty()) {
            log.warn("The container {} to be deleted is not existed.", containerName);
            return;
        }

        Container container = containers.get(0);
        if (!container.getState().equals("running")) {
            dockerClient.removeContainerCmd(containerName).exec();
        } else {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        }
    }

    @Override
    public void startItem(ResourceItem item) {
        String containerName = item.getName();
        DockerClient dockerClient = newDockerClient(item.getResourceServer().getHost());
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true)
                .withFilter("name", Arrays.asList(containerName)).exec();
        if (containers.isEmpty()) {
            throw new WecubeCoreException(String
                    .format("Failed to start container with name [%s] : Container is not exists.", containerName));
        }

        Container container = containers.get(0);
        if (!container.getState().equals("running")) {
            dockerClient.startContainerCmd(containerName).exec();
        } else {
            log.warn("The container {} is already running.", containerName);
        }
    }

    @Override
    public void stopItem(ResourceItem item) {
        String containerName = item.getName();
        DockerClient dockerClient = newDockerClient(item.getResourceServer().getHost());
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true)
                .withFilter("name", Arrays.asList(containerName)).exec();
        if (containers.isEmpty()) {
            throw new WecubeCoreException(String
                    .format("Failed to start container with name [%s] : Container is not exists.", containerName));
        }

        Container container = containers.get(0);
        if (container.getState().equals("running")) {
            dockerClient.stopContainerCmd(containerName).exec();
        } else {
            log.warn("The container {} is already stopped.", containerName);
        }
    }

    @Override
    public ResourceItem retrieveItem(ResourceItem item) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResourceItem updateItem(ResourceItem item) {
        switch (ResourceItemStatus.fromCode(item.getStatus())) {
        case RUNNING:
            startItem(item);
            break;
        case STOPPED:
            stopItem(item);
            break;
        default:
            break;
        }
        return item;
    }
}
