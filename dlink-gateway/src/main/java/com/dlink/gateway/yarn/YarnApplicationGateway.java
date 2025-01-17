/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.dlink.gateway.yarn;

import cn.hutool.core.io.FileUtil;
import com.dlink.assertion.Asserts;
import com.dlink.gateway.GatewayType;
import com.dlink.gateway.config.AppConfig;
import com.dlink.gateway.config.GatewayConfig;
import com.dlink.gateway.result.GatewayResult;
import com.dlink.gateway.result.YarnResult;
import com.dlink.model.SystemConfiguration;
import com.dlink.utils.LogUtil;
import org.apache.flink.client.deployment.ClusterSpecification;
import org.apache.flink.client.deployment.application.ApplicationConfiguration;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.ClusterClientProvider;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.yarn.YarnClientYarnClusterInformationRetriever;
import org.apache.flink.yarn.YarnClusterDescriptor;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptReport;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerReport;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * YarnApplicationGateway
 *
 * @author wenmo
 * @since 2021/10/29
 **/
public class YarnApplicationGateway extends YarnGateway {

    private static final Logger logger = LoggerFactory.getLogger(YarnApplicationGateway.class);

    public YarnApplicationGateway(GatewayConfig config) {
        super(config);
    }

    public YarnApplicationGateway() {
    }

    @Override
    public GatewayType getType() {
        return GatewayType.YARN_APPLICATION;
    }

    @Override
    public GatewayResult submitJar() {
        if (Asserts.isNull(yarnClient)) {
            init();
        }
        YarnResult result = YarnResult.build(getType());
        AppConfig appConfig = config.getAppConfig();
        configuration.set(PipelineOptions.JARS, Collections.singletonList(appConfig.getUserJarPath()));
        String[] userJarParas = appConfig.getUserJarParas();
        if (Asserts.isNull(userJarParas)) {
            userJarParas = new String[0];
        }
        ApplicationConfiguration applicationConfiguration = new ApplicationConfiguration(userJarParas,
                appConfig.getUserJarMainAppClass());
        YarnClusterDescriptor yarnClusterDescriptor = new YarnClusterDescriptor(
                configuration, yarnConfiguration, yarnClient,
                YarnClientYarnClusterInformationRetriever.create(yarnClient), true);

        ClusterSpecification.ClusterSpecificationBuilder clusterSpecificationBuilder = new ClusterSpecification.ClusterSpecificationBuilder();
        if (configuration.contains(JobManagerOptions.TOTAL_PROCESS_MEMORY)) {
            clusterSpecificationBuilder
                    .setMasterMemoryMB(configuration.get(JobManagerOptions.TOTAL_PROCESS_MEMORY).getMebiBytes());
        }
        if (configuration.contains(TaskManagerOptions.TOTAL_PROCESS_MEMORY)) {
            clusterSpecificationBuilder
                    .setTaskManagerMemoryMB(configuration.get(TaskManagerOptions.TOTAL_PROCESS_MEMORY).getMebiBytes());
        }
        if (configuration.contains(TaskManagerOptions.NUM_TASK_SLOTS)) {
            clusterSpecificationBuilder.setSlotsPerTaskManager(configuration.get(TaskManagerOptions.NUM_TASK_SLOTS))
                    .createClusterSpecification();
        }
        if (Asserts.isNotNull(config.getJarPaths())) {
            yarnClusterDescriptor
                    .addShipFiles(Arrays.stream(config.getJarPaths()).map(FileUtil::file).collect(Collectors.toList()));
        }

        try {
            ClusterClientProvider<ApplicationId> clusterClientProvider = yarnClusterDescriptor.deployApplicationCluster(
                    clusterSpecificationBuilder.createClusterSpecification(),
                    applicationConfiguration);
            ClusterClient<ApplicationId> clusterClient = clusterClientProvider.getClusterClient();
            Collection<JobStatusMessage> jobStatusMessages = clusterClient.listJobs().get();
            int counts = SystemConfiguration.getInstances().getJobIdWait();
            while (jobStatusMessages.size() == 0 && counts > 0) {
                Thread.sleep(1000);
                counts--;
                jobStatusMessages = clusterClient.listJobs().get();
                if (jobStatusMessages.size() > 0) {
                    break;
                }
            }
            if (jobStatusMessages.size() > 0) {
                List<String> jids = new ArrayList<>();
                for (JobStatusMessage jobStatusMessage : jobStatusMessages) {
                    jids.add(jobStatusMessage.getJobId().toHexString());
                }
                result.setJids(jids);
            }
            ApplicationId applicationId = clusterClient.getClusterId();
            result.setAppId(applicationId.toString());
            result.setWebURL(clusterClient.getWebInterfaceURL());
            saveYarnLog(applicationId);
            result.success();
        } catch (Exception e) {
            result.fail(LogUtil.getError(e));
        } finally {
            yarnClusterDescriptor.close();
        }
        return result;
    }

    public void saveYarnLog(ApplicationId applicationId) throws IOException, YarnException {
        YarnClient initYarnClient = initYarnClient();
        List<ApplicationAttemptReport> applicationAttempts = initYarnClient.getApplicationAttempts(applicationId);
        if (applicationAttempts.isEmpty()) {
            logger.info("{} is not init containers.", applicationId);
        } else {
            ApplicationAttemptId applicationAttemptId = applicationAttempts.get(0).getApplicationAttemptId();
            try {
                List<ContainerReport> containers = getContainersWithRetry(initYarnClient, applicationAttemptId);
                if (containers.isEmpty()) {
                    logger.info("{} is not get containers.", applicationAttemptId);
                    return;
                }
                logger.info("containers size : {}", containers.size());
                containers.forEach(containerReport -> {
                    String logUrl = containerReport.getLogUrl();
                    String logDir = System.getProperty("kso.output.yarn.logs.dir");
                    String dir = logDir + "/" + applicationId.toString() + "/" + containerReport.getContainerId().toString();
                    File logFileDir = new File(dir);
                    if (!logFileDir.exists() || !logFileDir.isDirectory()) {
                        boolean mkdirs = logFileDir.mkdirs();
                        if (mkdirs) {
                            logger.info("{} dir is create success.", dir);
                        } else {
                            logger.info("{} dir is create fail.", dir);
                        }
                    }
                    String filePath = dir + "/" + containerReport.getContainerId().toString() + ".txt";
                    File file = new File(filePath);
                    if (!file.exists() || !file.isFile()) {
                        File logUrlFile = FileUtil.writeUtf8String(logUrl, filePath);
                        logger.info("create file {}", logUrlFile.getAbsolutePath());
                    }
                });
            } catch (YarnException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private YarnClient initYarnClient() {
        YarnConfiguration yarnConfiguration = new YarnConfiguration();
        yarnConfiguration
                .addResource(new Path(URI.create(config.getClusterConfig().getYarnConfigPath() + "/yarn-site.xml")));
        yarnConfiguration
                .addResource(new Path(URI.create(config.getClusterConfig().getYarnConfigPath() + "/core-site.xml")));
        yarnConfiguration
                .addResource(new Path(URI.create(config.getClusterConfig().getYarnConfigPath() + "/hdfs-site.xml")));
        YarnClient yarnClient = YarnClient.createYarnClient();
        yarnClient.init(yarnConfiguration);
        yarnClient.start();
        return yarnClient;
    }

    private List<ContainerReport> getContainersWithRetry(YarnClient yarnClient, ApplicationAttemptId applicationAttemptId) throws IOException, YarnException {
        List<ContainerReport> containers;
        int retryCount = 0;
        final int maxRetries = 10; // 最大重试次数
        final long retryIntervalMillis = 10000; // 重试间隔时间（10秒）
        do {
            containers = yarnClient.getContainers(applicationAttemptId);
            if (containers.size() > 1) {
                break;
            }
            retryCount++;
            try {
                Thread.sleep(retryIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted while waiting for containers", e);
            }
        } while (retryCount < maxRetries);

        if (containers.size() <= 1) {
            throw new RuntimeException("Failed to get more than one container after " + maxRetries + " retries");
        }

        return containers;
    }
}
