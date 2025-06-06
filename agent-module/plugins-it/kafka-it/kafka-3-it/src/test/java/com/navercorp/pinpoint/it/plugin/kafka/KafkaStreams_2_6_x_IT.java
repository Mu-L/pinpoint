/*
 * Copyright 2023 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.it.plugin.kafka;

import com.navercorp.pinpoint.it.plugin.utils.AgentPath;
import com.navercorp.pinpoint.it.plugin.utils.TestcontainersOption;
import com.navercorp.pinpoint.test.plugin.Dependency;
import com.navercorp.pinpoint.test.plugin.PinpointAgent;
import com.navercorp.pinpoint.test.plugin.PinpointConfig;
import com.navercorp.pinpoint.test.plugin.PluginTest;
import com.navercorp.pinpoint.test.plugin.shared.SharedDependency;
import com.navercorp.pinpoint.test.plugin.shared.SharedTestLifeCycleClass;
import org.junit.jupiter.api.Test;
import test.pinpoint.plugin.kafka.KafkaStreamsUnitServer;
import test.pinpoint.plugin.kafka.TestProducer;

import java.util.Random;

import static test.pinpoint.plugin.kafka.KafkaITConstants.TRACE_TYPE_MULTI_RECORDS;
import static test.pinpoint.plugin.kafka.KafkaITConstants.TRACE_TYPE_RECORD;

@PluginTest
@PinpointAgent(AgentPath.PATH)
@PinpointConfig("pinpoint-kafka-client.config")
@Dependency({
        "org.apache.kafka:kafka_2.12:[2.6.0]",
        "org.apache.kafka:kafka-clients:[2.6.0]", "org.apache.kafka:kafka-streams:[2.6.0,2.6.1]"
})
@SharedDependency({"org.apache.kafka:kafka-streams:2.5.0",
        TestcontainersOption.TEST_CONTAINER, TestcontainersOption.KAFKA})
@SharedTestLifeCycleClass(KafkaStreamsUnitServer.class)
public class KafkaStreams_2_6_x_IT extends KafkaStreamsIT {
    Random random = new Random();
    @Test
    public void streamsProducerSendTest() throws NoSuchMethodException {
        int messageCount = random.nextInt(5) + 1;
        final TestProducer producer = new TestProducer(brokerUrl);

        producer.sendMessageForStream(messageCount, TRACE_TYPE_RECORD);
        KafkaStreamsITBase.verifyProducerSend(brokerUrl, messageCount);
    }

    @Test
    public void streamsConsumeTest() throws NoSuchMethodException {
        final TestProducer producer = new TestProducer(brokerUrl);
        producer.sendMessageForStream(1, TRACE_TYPE_MULTI_RECORDS);
        KafkaStreamsITBase.verifyMultiConsumerEntryPoint(brokerUrl);
    }
}
