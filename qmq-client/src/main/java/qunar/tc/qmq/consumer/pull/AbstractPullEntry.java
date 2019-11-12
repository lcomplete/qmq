/*
 * Copyright 2018 Qunar, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qunar.tc.qmq.consumer.pull;

import static qunar.tc.qmq.metrics.MetricsConstants.SUBJECT_GROUP_ARRAY;

import com.google.common.base.Strings;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import qunar.tc.qmq.ClientType;
import qunar.tc.qmq.ConsumeStrategy;
import qunar.tc.qmq.PullEntry;
import qunar.tc.qmq.base.BaseMessage;
import qunar.tc.qmq.broker.BrokerGroupInfo;
import qunar.tc.qmq.broker.BrokerService;
import qunar.tc.qmq.config.PullSubjectsConfig;
import qunar.tc.qmq.metrics.Metrics;
import qunar.tc.qmq.metrics.QmqCounter;
import qunar.tc.qmq.protocol.CommandCode;

/**
 * @author yiqun.fan create on 17-11-2.
 */
abstract class AbstractPullEntry extends AbstractPullClient implements PullEntry {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractPullEntry.class);

    private static final int MAX_MESSAGE_RETRY_THRESHOLD = 5;

    protected final PullService pullService;
    protected final AckSendQueue ackSendQueue;
    protected final BrokerService brokerService;

    private final AckService ackService;

    private final AtomicReference<Integer> pullRequestTimeout;

    protected final QmqCounter pullWorkCounter;
    protected final QmqCounter pullFailCounter;

    AbstractPullEntry(
            String subject,
            String consumerGroup,
            String partitionName,
            String brokerGroup,
            String consumerId,
            ConsumeStrategy consumeStrategy,
            int allocationVersion,
            boolean isBroadcast,
            boolean isOrdered,
            int partitionSetVersion,
            long consumptionExpiredTime,
            PullService pullService,
            AckService ackService,
            BrokerService brokerService,
            SendMessageBack sendMessageBack) {
        super(subject, consumerGroup, partitionName, brokerGroup, consumerId, consumeStrategy, allocationVersion, isBroadcast,
                isOrdered, partitionSetVersion, consumptionExpiredTime);
        this.pullService = pullService;
        this.ackService = ackService;
        this.brokerService = brokerService;

        if (!StringUtils.isEmpty(subject)) {
            AckSendQueue queue = new AckSendQueue(subject, consumerGroup, partitionName, brokerGroup, consumeStrategy,
                    ackService, this.brokerService, sendMessageBack, isBroadcast, isOrdered);
            queue.init();
            this.ackSendQueue = queue;
        } else {
            this.ackSendQueue = null;
        }

        pullRequestTimeout = PullSubjectsConfig.get().getPullRequestTimeout(subject);

        String[] values = new String[]{subject, consumerGroup};
        this.pullWorkCounter = Metrics.counter("qmq_pull_work_count", SUBJECT_GROUP_ARRAY, values);
        this.pullFailCounter = Metrics.counter("qmq_pull_fail_count", SUBJECT_GROUP_ARRAY, values);
    }

    protected void markFailed(BrokerGroupInfo group) {
        pullFailCounter.inc();
        group.markFailed();
    }

    protected PullParam buildPullParam(ConsumeParam consumeParam, BrokerGroupInfo pullBrokerGroup,
            AckSendInfo ackSendInfo, int pullSize, int pullTimeout) {
        return new PullParam.PullParamBuilder()
                .setConsumeParam(consumeParam)
                .setBrokerGroup(pullBrokerGroup)
                .setPullBatchSize(pullSize)
                .setTimeoutMillis(pullTimeout)
                .setRequestTimeoutMillis(pullRequestTimeout.get())
                .setMinPullOffset(ackSendInfo.getMinPullOffset())
                .setMaxPullOffset(ackSendInfo.getMaxPullOffset())
                .setPartitionName(getPartitionName())
                .setConsumeStrategy(getConsumeStrategy())
                .setAllocationVersion(getConsumerAllocationVersion())
                .create();
    }

    protected List<PulledMessage> handlePullResult(final PullParam pullParam, final PullResult pullResult,
            final AckHook ackHook) {
        if (pullResult.getResponseCode() == CommandCode.BROKER_REJECT) {
            pullResult.getBrokerGroup().setAvailable(false);
            brokerService.refreshMetaInfo(ClientType.CONSUMER, pullParam.getSubject(), pullParam.getGroup());
        }

        if (pullResult.getResponseCode() == CommandCode.ACQUIRE_LOCK_FAILED) {
            LOGGER.error("获取独占锁失败 subject {} partition {} consumerGroup {} brokerGroup {}", getSubject(), getPartitionName(), getConsumerGroup(), getBrokerGroup());
        }

        List<BaseMessage> messages = pullResult.getMessages();
        if (messages != null && !messages.isEmpty()) {
            monitorMessageCount(pullParam, pullResult);
            PulledMessageFilter filter = new PulledMessageFilterImpl(pullParam);
            List<PulledMessage> pulledMessages = ackService
                    .buildPulledMessages(pullParam, pullResult, ackSendQueue, ackHook, filter);
            if (pulledMessages == null || pulledMessages.isEmpty()) {
                return Collections.emptyList();
            }
            logTimes(pulledMessages);
            return pulledMessages;
        }
        return Collections.emptyList();
    }

    private void logTimes(List<PulledMessage> pulledMessages) {
        for (PulledMessage pulledMessage : pulledMessages) {
            int times = pulledMessage.times();
            if (times > MAX_MESSAGE_RETRY_THRESHOLD) {
                LOGGER.warn("这是第 {} 次收到同一条消息，请注意检查逻辑是否有问题. subject={}, msgId={}",
                        times, pulledMessage.getSubject(), pulledMessage.getMessageId());
            }
        }
    }

    protected static void monitorMessageCount(final PullParam pullParam, final PullResult pullResult) {
        try {
            Metrics.counter("qmq_pull_message_count", new String[]{"subject", "group", "broker"},
                    new String[]{pullParam.getSubject(), pullParam.getGroup(),
                            pullParam.getBrokerGroup().getGroupName()})
                    .inc(pullResult.getMessages().size());
        } catch (Exception e) {
            LOGGER.error("AbstractPullEntry monitor exception", e);
        }
    }

    private static final class PulledMessageFilterImpl implements PulledMessageFilter {

        private final PullParam pullParam;

        PulledMessageFilterImpl(PullParam pullParam) {
            this.pullParam = pullParam;
        }

        @Override
        public boolean filter(PulledMessage message) {
            if (pullParam.isConsumeMostOnce() && message.times() > 1) {
                return false;
            }

            //反序列化失败，跳过这个消息
            if (message.getBooleanProperty(BaseMessage.keys.qmq_corruptData.name())) {
                return false;
            }

            // qmq_consumerGroupName
            String group = message.getStringProperty(BaseMessage.keys.qmq_consumerGroupName);
            return Strings.isNullOrEmpty(group) || group.equals(pullParam.getGroup());
        }

    }

    @Override
    public void destroy() {
        super.destroy();
        this.ackSendQueue.destroy(TimeUnit.SECONDS.toMillis(5));
        this.brokerService.releaseLock(getSubject(), getConsumerGroup(), getPartitionName(), getBrokerGroup(),
                getConsumeStrategy());
    }

    @Override
    public void afterOffline() {
        try {
            ackSendQueue.trySendAck(3000);
            brokerService.releaseLock(getSubject(), getConsumerGroup(), getPartitionName(), getBrokerGroup(),
                    getConsumeStrategy());
        } catch (Exception e) {
            LOGGER.error("offline error", e);
        }
    }
}
