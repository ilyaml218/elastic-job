/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.job.lite.internal.server;

import com.dangdang.ddframe.job.util.env.LocalHostService;
import com.dangdang.ddframe.job.lite.api.config.JobConfigurationFactory;
import com.dangdang.ddframe.job.lite.fixture.TestJob;
import com.dangdang.ddframe.job.lite.internal.election.LeaderElectionService;
import com.dangdang.ddframe.job.lite.internal.execution.ExecutionService;
import com.dangdang.ddframe.job.lite.internal.schedule.JobRegistry;
import com.dangdang.ddframe.job.lite.internal.schedule.JobScheduleController;
import com.dangdang.ddframe.job.lite.internal.server.JobOperationListenerManager.ConnectionLostListener;
import com.dangdang.ddframe.job.lite.internal.server.JobOperationListenerManager.JobPausedStatusJobListener;
import com.dangdang.ddframe.job.lite.internal.sharding.ShardingService;
import com.dangdang.ddframe.job.lite.internal.storage.JobNodeStorage;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.state.ConnectionState;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.unitils.util.ReflectionUtils;

import java.util.Arrays;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class JobOperationListenerManagerTest {
    
    @Mock
    private JobNodeStorage jobNodeStorage;
    
    @Mock
    private LeaderElectionService leaderElectionService;
    
    @Mock
    private ServerService serverService;
    
    @Mock
    private ShardingService shardingService;
    
    @Mock
    private ExecutionService executionService;
    
    @Mock
    private JobScheduleController jobScheduleController;
    
    private String ip = new LocalHostService().getIp();
    
    private final JobOperationListenerManager jobOperationListenerManager = new JobOperationListenerManager(null, 
            JobConfigurationFactory.createSimpleJobConfigurationBuilder("testJob", TestJob.class, 3, "0/1 * * * * ?").build());
    
    @Before
    public void setUp() throws NoSuchFieldException {
        MockitoAnnotations.initMocks(this);
        ReflectionUtils.setFieldValue(jobOperationListenerManager, "leaderElectionService", leaderElectionService);
        ReflectionUtils.setFieldValue(jobOperationListenerManager, "serverService", serverService);
        ReflectionUtils.setFieldValue(jobOperationListenerManager, "shardingService", shardingService);
        ReflectionUtils.setFieldValue(jobOperationListenerManager, "executionService", executionService);
        ReflectionUtils.setFieldValue(jobOperationListenerManager, jobOperationListenerManager.getClass().getSuperclass().getDeclaredField("jobNodeStorage"), jobNodeStorage);
    }
    
    @Test
    public void assertStart() {
        jobOperationListenerManager.start();
        verify(jobNodeStorage).addConnectionStateListener(Matchers.<ConnectionLostListener>any());
        verify(jobNodeStorage, times(3)).addDataListener(Matchers.<JobPausedStatusJobListener>any());
    }
    
    @Test
    public void assertConnectionLostListenerWhenConnectionStateIsLost() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        jobOperationListenerManager.new ConnectionLostListener().stateChanged(null, ConnectionState.LOST);
        verify(jobScheduleController).pauseJob();
    }
    
    @Test
    public void assertConnectionLostListenerWhenConnectionStateIsReconnectedAndIsNotPausedManually() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        when(shardingService.getLocalHostShardingItems()).thenReturn(Arrays.asList(0, 1));
        when(serverService.isJobPausedManually()).thenReturn(false);
        jobOperationListenerManager.new ConnectionLostListener().stateChanged(null, ConnectionState.RECONNECTED);
        verify(serverService).persistServerOnline();
        verify(executionService).clearRunningInfo(Arrays.asList(0, 1));
        verify(jobScheduleController).resumeJob();
    }
    
    @Test
    public void assertConnectionLostListenerWhenConnectionStateIsReconnectedAndIsPausedManually() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        when(shardingService.getLocalHostShardingItems()).thenReturn(Arrays.asList(0, 1));
        when(serverService.isJobPausedManually()).thenReturn(true);
        jobOperationListenerManager.new ConnectionLostListener().stateChanged(null, ConnectionState.RECONNECTED);
        verify(serverService).persistServerOnline();
        verify(executionService).clearRunningInfo(Arrays.asList(0, 1));
        verify(jobScheduleController, times(0)).resumeJob();
    }
    
    @Test
    public void assertConnectionLostListenerWhenConnectionStateIsOther() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        jobOperationListenerManager.new ConnectionLostListener().stateChanged(null, ConnectionState.CONNECTED);
        verify(jobScheduleController, times(0)).pauseJob();
        verify(jobScheduleController, times(0)).resumeJob();
    }
    
    @Test
    public void assertJobTriggerStatusJobListenerWhenRemove() {
        jobOperationListenerManager.new JobTriggerStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_REMOVED, new ChildData("/testJob/servers/" + ip + "/trigger", null, "".getBytes())), "/testJob/servers/" + ip + "/trigger");
        verify(serverService, times(0)).clearJobTriggerStatus();
        verify(jobScheduleController, times(0)).triggerJob();
    }
    
    @Test
    public void assertJobTriggerStatusJobListenerWhenIsAddButNotLocalHostJobTriggerPath() {
        jobOperationListenerManager.new JobTriggerStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_ADDED, new ChildData("/testJob/servers/" + ip + "/other", null, "".getBytes())), "/testJob/servers/" + ip + "/other");
        verify(serverService, times(0)).clearJobTriggerStatus();
        verify(jobScheduleController, times(0)).triggerJob();
    }
    
    @Test
    public void assertJobTriggerStatusJobListenerWhenIsAddAndIsJobLocalHostTriggerPathButNoJobRegister() {
        jobOperationListenerManager.new JobTriggerStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_ADDED, new ChildData("/testJob/servers/" + ip + "/trigger", null, "".getBytes())), "/testJob/servers/" + ip + "/trigger");
        verify(serverService).clearJobTriggerStatus();
        verify(jobScheduleController, times(0)).triggerJob();
    }
    
    @Test
    public void assertJobTriggerStatusJobListenerWhenIsAddAndIsJobLocalHostTriggerPathAndJobRegisterButServerIsNotReady() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        jobOperationListenerManager.new JobTriggerStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_ADDED, new ChildData("/testJob/servers/" + ip + "/trigger", null, "".getBytes())), "/testJob/servers/" + ip + "/trigger");
        verify(serverService).clearJobTriggerStatus();
        verify(serverService).isLocalhostServerReady();
        verify(jobScheduleController, times(0)).triggerJob();
    }
    
    @Test
    public void assertJobTriggerStatusJobListenerWhenIsAddAndIsJobLocalHostTriggerPathAndJobRegisterAndServerIsReady() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        when(serverService.isLocalhostServerReady()).thenReturn(true);
        jobOperationListenerManager.new JobTriggerStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_ADDED, new ChildData("/testJob/servers/" + ip + "/trigger", null, "".getBytes())), "/testJob/servers/" + ip + "/trigger");
        verify(serverService).isLocalhostServerReady();
        verify(jobScheduleController).triggerJob();
        verify(serverService).clearJobTriggerStatus();
    }
    
    @Test
    public void assertJobPausedStatusJobListenerWhenIsNotJobPausedPath() {
        jobOperationListenerManager.new JobPausedStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_UPDATED, new ChildData("/testJob/servers/" + ip + "/other", null, "".getBytes())), "/testJob/servers/" + ip + "/other");
        verify(jobScheduleController, times(0)).pauseJob();
        verify(jobScheduleController, times(0)).resumeJob();
    }
    
    @Test
    public void assertJobPausedStatusJobListenerWhenIsJobPausedPathButJobIsNotExisted() {
        jobOperationListenerManager.new JobPausedStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_ADDED, new ChildData("/testJob/servers/" + ip + "/paused", null, "".getBytes())), "/testJob/servers/" + ip + "/paused");
        verify(jobScheduleController, times(0)).pauseJob();
        verify(jobScheduleController, times(0)).resumeJob();
    }
    
    @Test
    public void assertJobPausedStatusJobListenerWhenIsJobPausedPathAndUpdate() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        jobOperationListenerManager.new JobPausedStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_UPDATED, new ChildData("/testJob/servers/" + ip + "/paused", null, "".getBytes())), "/testJob/servers/" + ip + "/paused");
        verify(jobScheduleController, times(0)).pauseJob();
        verify(jobScheduleController, times(0)).resumeJob();
    }
    
    @Test
    public void assertJobPausedStatusJobListenerWhenIsJobPausedPathAndAdd() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        jobOperationListenerManager.new JobPausedStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_ADDED, new ChildData("/testJob/servers/" + ip + "/paused", null, "".getBytes())), "/testJob/servers/" + ip + "/paused");
        verify(jobScheduleController).pauseJob();
        verify(jobScheduleController, times(0)).resumeJob();
    }
    
    @Test
    public void assertJobPausedStatusJobListenerWhenIsJobPausedPathAndRemove() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        jobOperationListenerManager.new JobPausedStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_REMOVED, new ChildData("/testJob/servers/" + ip + "/paused", null, "".getBytes())), "/testJob/servers/" + ip + "/paused");
        verify(jobScheduleController, times(0)).pauseJob();
        verify(jobScheduleController).resumeJob();
        verify(serverService).clearJobPausedStatus();
    }
    
    @Test
    public void assertJobShutdownStatusJobListenerWhenIsNotJobShutdownPath() {
        jobOperationListenerManager.new JobShutdownStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_UPDATED, new ChildData("/testJob/servers/" + ip + "/other", null, "".getBytes())), "/testJob/servers/" + ip + "/other");
        verify(jobScheduleController, times(0)).shutdown();
    }
    
    @Test
    public void assertJobShutdownStatusJobListenerWhenIsJobShutdownPathButJobIsNotExisted() {
        jobOperationListenerManager.new JobShutdownStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_ADDED, new ChildData("/testJob/servers/" + ip + "/shutdown", null, "".getBytes())), "/testJob/servers/" + ip + "/shutdown");
        verify(jobScheduleController, times(0)).shutdown();
    }
    
    @Test
    public void assertJobShutdownStatusJobListenerWhenIsJobShutdownPathAndUpdate() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        jobOperationListenerManager.new JobShutdownStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_UPDATED, new ChildData("/testJob/servers/" + ip + "/shutdown", null, "".getBytes())), "/testJob/servers/" + ip + "/shutdown");
        verify(jobScheduleController, times(0)).shutdown();
    }
    
    @Test
    public void assertJobShutdownStatusJobListenerWhenIsJobShutdownPathAndAdd() {
        JobRegistry.getInstance().addJobScheduleController("testJob", jobScheduleController);
        jobOperationListenerManager.new JobShutdownStatusJobListener().dataChanged(null, new TreeCacheEvent(
                TreeCacheEvent.Type.NODE_ADDED, new ChildData("/testJob/servers/" + ip + "/shutdown", null, "".getBytes())), "/testJob/servers/" + ip + "/shutdown");
        verify(jobScheduleController).shutdown();
        verify(serverService).processServerShutdown();
    }
}
