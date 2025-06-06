/*
 * Copyright 2021 NAVER Corp.
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

package com.navercorp.pinpoint.plugin.kafka;

import com.navercorp.pinpoint.bootstrap.async.AsyncContextAccessor;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentClass;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentException;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentMethod;
import com.navercorp.pinpoint.bootstrap.instrument.Instrumentor;
import com.navercorp.pinpoint.bootstrap.instrument.MethodFilter;
import com.navercorp.pinpoint.bootstrap.instrument.MethodFilters;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformCallback;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformCallbackParameters;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformCallbackParametersBuilder;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformTemplate;
import com.navercorp.pinpoint.bootstrap.instrument.transformer.TransformTemplateAware;
import com.navercorp.pinpoint.bootstrap.interceptor.BasicMethodInterceptor;
import com.navercorp.pinpoint.bootstrap.interceptor.scope.ExecutionPolicy;
import com.navercorp.pinpoint.bootstrap.logging.PluginLogManager;
import com.navercorp.pinpoint.bootstrap.logging.PluginLogger;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPlugin;
import com.navercorp.pinpoint.bootstrap.plugin.ProfilerPluginSetupContext;
import com.navercorp.pinpoint.common.util.StringUtils;
import com.navercorp.pinpoint.plugin.kafka.field.accessor.EndPointFieldAccessor;
import com.navercorp.pinpoint.plugin.kafka.field.accessor.RemoteAddressFieldAccessor;
import com.navercorp.pinpoint.plugin.kafka.field.accessor.SocketChannelListFieldAccessor;
import com.navercorp.pinpoint.plugin.kafka.field.getter.ApiVersionsGetter;
import com.navercorp.pinpoint.plugin.kafka.field.getter.RecordCollectorGetter;
import com.navercorp.pinpoint.plugin.kafka.field.getter.SelectorGetter;
import com.navercorp.pinpoint.plugin.kafka.field.getter.StampedRecordGetter;
import com.navercorp.pinpoint.plugin.kafka.interceptor.AddRecordsToTasksInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ConsumerConstructorInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ConsumerConstructor_V_2_7_Interceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ConsumerMultiRecordEntryPointInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ConsumerPollInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ConsumerRecordEntryPointInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ConsumerRecordsInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.FetchResponseInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ListenerConsumerInvokeErrorHandlerInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.NetworkClientPollInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ProcessInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ProducerAddHeaderInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ProducerConstructorInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.ProducerSendInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.RecordCollectorSendInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.RecordDeserializerInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.SocketChannelCloseInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.SocketChannelRegisterInterceptor;
import com.navercorp.pinpoint.plugin.kafka.interceptor.StreamTaskDoProcessInterceptor;

import java.security.ProtectionDomain;
import java.util.List;

import static com.navercorp.pinpoint.common.util.VarArgs.va;

/**
 * @author Harris Gwag (gwagdalf)
 * @author Taejin Koo
 */
public class KafkaPlugin implements ProfilerPlugin, TransformTemplateAware {
    private final static String SCOPE_KAFKA_CONSUMER_POLL = "SCOPE_KAFKA_CONSUMER_POLL";
    private final static String SCOPE_KAFKA_RECORD_COLLECTOR = "SCOPE_KAFKA_RECORD_COLLECTOR";
    private final PluginLogger logger = PluginLogManager.getLogger(this.getClass());

    private TransformTemplate transformTemplate;

    @Override
    public void setup(ProfilerPluginSetupContext context) {
        final KafkaConfig config = new KafkaConfig(context.getConfig());
        if (Boolean.FALSE == config.isEnable()) {
            logger.info("{} disabled", this.getClass().getSimpleName());
            return;
        }
        logger.info("{} config:{}", this.getClass().getSimpleName(), config);

        if (config.isStreamsEnable()) {
            TransformCallbackParameters parameters = TransformCallbackParametersBuilder.newBuilder()
                    .addBoolean(config.isTraceStreamProcess())
                    .toParameters();
            transformTemplate.transform("org.apache.kafka.streams.processor.internals.StreamTask", StreamTaskTransform.class, parameters);
            transformTemplate.transform("org.apache.kafka.streams.processor.internals.RecordCollectorImpl", RecordCollectorTransform.class);
            transformTemplate.transform("org.apache.kafka.streams.processor.internals.RecordDeserializer", RecordDeserializerTransform.class);
            transformTemplate.transform("org.apache.kafka.streams.processor.internals.StampedRecord", StampedRecordTransform.class);
        }
        if (config.isProducerEnable()) {
            transformTemplate.transform("org.apache.kafka.clients.producer.KafkaProducer", KafkaProducerTransform.class);
            transformTemplate.transform("org.apache.kafka.clients.producer.internals.TransactionManager", TransactionManagerTransform.class);
        }
        if (enableConsumerTransform(config)) {
            transformTemplate.transform("org.apache.kafka.clients.consumer.KafkaConsumer", KafkaConsumerTransform.class);
            transformTemplate.transform("org.apache.kafka.clients.consumer.ConsumerRecord", ConsumerRecordTransform.class);
            // for getting local addresses
            transformTemplate.transform("org.apache.kafka.common.network.Selector", KafkaSelectorTransform.class);
            transformTemplate.transform("org.apache.kafka.clients.NetworkClient", NetworkClientTransform.class);
            transformTemplate.transform("org.apache.kafka.common.TopicPartition", TopicPartitionTransform.class);
            transformTemplate.transform("org.apache.kafka.clients.consumer.ConsumerRecords", ConsumerRecordsTransform.class);
            transformTemplate.transform("org.apache.kafka.common.requests.FetchResponse", FetchResponseTransform.class);
            if (config.isSpringConsumerEnable()) {
                if (config.isKafkaMessageListenerContainerEnable()) {
                    // KafkaMessageListenerContainer$ListenerConsumer
                    transformTemplate.transform("org.springframework.kafka.listener.KafkaMessageListenerContainer$ListenerConsumer", ListenerConsumerTransform.class);
                }
                transformTemplate.transform("org.springframework.kafka.listener.adapter.RecordMessagingMessageListenerAdapter", AcknowledgingConsumerAwareMessageListenerTransform.class);
                transformTemplate.transform("org.springframework.kafka.listener.adapter.BatchMessagingMessageListenerAdapter", BatchMessagingMessageListenerAdapterTransform.class);
                // Spring Cloud Starter Stream Kafka 2.2.x is supported
                transformTemplate.transform("org.springframework.kafka.listener.adapter.RetryingMessageListenerAdapter", AcknowledgingConsumerAwareMessageListenerTransform.class);
                // for MessagingGatewaySupport in spring-integration-kafka
                transformTemplate.transform("org.springframework.integration.kafka.inbound.KafkaInboundGateway$IntegrationRecordMessageListener", AcknowledgingConsumerAwareMessageListenerTransform.class);
                // for MessageProducerSupport in spring-integration-kafka
                transformTemplate.transform("org.springframework.integration.kafka.inbound.KafkaMessageDrivenChannelAdapter$IntegrationRecordMessageListener", AcknowledgingConsumerAwareMessageListenerTransform.class);
                transformTemplate.transform("org.springframework.integration.kafka.inbound.KafkaMessageDrivenChannelAdapter$IntegrationBatchMessageListener", BatchMessagingMessageListenerAdapterTransform.class);
            }
            if (StringUtils.hasText(config.getKafkaEntryPoint())) {
                transformEntryPoint(config.getKafkaEntryPoint());
            }
        }
    }

    public static class FetchResponseTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);
            target.addField(EndPointFieldAccessor.class);

            // api version 13+
            InstrumentMethod responseData = target.getDeclaredMethod("responseData", "java.util.Map", "short");
            if (responseData == null) {
                //api version ~ 12
                responseData = target.getDeclaredMethod("responseData");
            }

            if (responseData != null) {
                responseData.addInterceptor(FetchResponseInterceptor.class);
            }

            return target.toBytecode();
        }
    }

    public static class RecordCollectorTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);
            target.addField(AsyncContextAccessor.class);

            // // send(final String topic, final K key, final V value, final Headers headers, final Long timestamp, final Serializer<K> keySerializer, final Serializer<V> valueSerializer, final StreamPartitioner<? super K, ? super V> partitioner)
            InstrumentMethod sendMethod1 = target.getDeclaredMethod("send", "java.lang.String", "java.lang.Object", "java.lang.Object", "org.apache.kafka.common.header.Headers", "java.lang.Long", "org.apache.kafka.common.serialization.Serializer", "org.apache.kafka.common.serialization.Serializer", "org.apache.kafka.streams.processor.StreamPartitioner");
            if (sendMethod1 != null) {
                sendMethod1.addScopedInterceptor(RecordCollectorSendInterceptor.class, SCOPE_KAFKA_RECORD_COLLECTOR);
            }
            // send(final String topic, final K key, final V value, final Headers headers, final Integer partition, final Long timestamp, final Serializer<K> keySerializer, final Serializer<V> valueSerializer)
            InstrumentMethod sendMethod2 = target.getDeclaredMethod("send", "java.lang.String", "java.lang.Object", "java.lang.Object", "org.apache.kafka.common.header.Headers", "java.lang.Integer", "java.lang.Long", "org.apache.kafka.common.serialization.Serializer", "org.apache.kafka.common.serialization.Serializer");
            if (sendMethod2 != null) {
                sendMethod2.addScopedInterceptor(RecordCollectorSendInterceptor.class, SCOPE_KAFKA_RECORD_COLLECTOR);
            }
            // over 3.3.x
            // send(final String topic, final K key, final V value, final Headers headers, final Long timestamp, final Serializer<K> keySerializer, final Serializer<V> valueSerializer, final String processorNodeId, final InternalProcessorContext<Void, Void> context, final StreamPartitioner<? super K, ? super V> partitioner)
            InstrumentMethod sendMethod3 = target.getDeclaredMethod("send", "java.lang.String", "java.lang.Object", "java.lang.Object", "org.apache.kafka.common.header.Headers", "java.lang.Long", "org.apache.kafka.common.serialization.Serializer", "org.apache.kafka.common.serialization.Serializer", "java.lang.String", "org.apache.kafka.streams.processor.internals.InternalProcessorContext", "org.apache.kafka.streams.processor.StreamPartitioner");
            if (sendMethod3 != null) {
                sendMethod3.addScopedInterceptor(RecordCollectorSendInterceptor.class, SCOPE_KAFKA_RECORD_COLLECTOR);
            }
            // send(final String topic, final K key, final V value, final Headers headers, final Integer partition, final Long timestamp, final Serializer<K> keySerializer, final Serializer<V> valueSerializer, final String processorNodeId, final InternalProcessorContext<Void, Void> context)
            InstrumentMethod sendMethod4 = target.getDeclaredMethod("send", "java.lang.String", "java.lang.Object", "java.lang.Object", "org.apache.kafka.common.header.Headers", "java.lang.Integer", "java.lang.Long", "org.apache.kafka.common.serialization.Serializer", "org.apache.kafka.common.serialization.Serializer", "java.lang.String", "org.apache.kafka.streams.processor.internals.InternalProcessorContext");
            if (sendMethod4 != null) {
                sendMethod4.addScopedInterceptor(RecordCollectorSendInterceptor.class, SCOPE_KAFKA_RECORD_COLLECTOR);
            }

            return target.toBytecode();
        }
    }

    public static class StreamTaskTransform implements TransformCallback {
        private final boolean traceStreamProcess;

        public StreamTaskTransform(Boolean traceStreamProcess) {
            this.traceStreamProcess = traceStreamProcess;
        }

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);
            target.addField(AsyncContextAccessor.class);
            if (target.hasField("record")) {
                target.addGetter(StampedRecordGetter.class, "record");
            }
            if (target.hasField("recordCollector")) {
                target.addGetter(RecordCollectorGetter.class, "recordCollector");
            }

            if (traceStreamProcess) {
                InstrumentMethod doProcessMethod = target.getDeclaredMethod("doProcess", "long");
                if (doProcessMethod != null) {
                    doProcessMethod.addInterceptor(StreamTaskDoProcessInterceptor.class);
                }
            } else {
                InstrumentMethod addRecordsMethod = target.getDeclaredMethod("addRecords", "org.apache.kafka.common.TopicPartition", "java.lang.Iterable");
                if (addRecordsMethod != null) {
                    addRecordsMethod.addInterceptor(AddRecordsToTasksInterceptor.class);
                }
                InstrumentMethod processMethod = target.getDeclaredMethod("process");
                if (processMethod != null) {
                    processMethod.addInterceptor(ProcessInterceptor.class);
                }
                // 2.6 +
                processMethod = target.getDeclaredMethod("process", "long");
                if (processMethod != null) {
                    processMethod.addInterceptor(ProcessInterceptor.class);
                }
            }

            return target.toBytecode();
        }
    }

    public static class RecordDeserializerTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            final InstrumentMethod deserializeMethod = target.getDeclaredMethod("deserialize", "org.apache.kafka.streams.processor.api.ProcessorContext", "org.apache.kafka.clients.consumer.ConsumerRecord");
            if (deserializeMethod != null) {
                deserializeMethod.addInterceptor(RecordDeserializerInterceptor.class);
            }

            return target.toBytecode();
        }
    }

    public static class StampedRecordTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);
            target.addField(RemoteAddressFieldAccessor.class);
            target.addField(EndPointFieldAccessor.class);

            return target.toBytecode();
        }
    }

    public static class KafkaProducerTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            // Version 2.8.0+ is supported.
            InstrumentMethod constructor = target.getConstructor("org.apache.kafka.clients.producer.ProducerConfig",
                    "org.apache.kafka.common.serialization.Serializer", "org.apache.kafka.common.serialization.Serializer",
                    "org.apache.kafka.clients.producer.internals.ProducerMetadata", "org.apache.kafka.clients.KafkaClient",
                    "org.apache.kafka.clients.producer.internals.ProducerInterceptors", "org.apache.kafka.common.utils.Time");

            if (constructor == null) {
                // Version 2.3.0+ is supported.
                constructor = target.getConstructor("java.util.Map",
                        "org.apache.kafka.common.serialization.Serializer", "org.apache.kafka.common.serialization.Serializer",
                        "org.apache.kafka.clients.producer.internals.ProducerMetadata", "org.apache.kafka.clients.KafkaClient",
                        "org.apache.kafka.clients.producer.internals.ProducerInterceptors", "org.apache.kafka.common.utils.Time");
            }

            if (constructor == null) {
                // Version 2.2.0+ is supported.
                constructor = target.getConstructor("java.util.Map",
                        "org.apache.kafka.common.serialization.Serializer", "org.apache.kafka.common.serialization.Serializer",
                        "org.apache.kafka.clients.Metadata", "org.apache.kafka.clients.KafkaClient",
                        "org.apache.kafka.clients.producer.internals.ProducerInterceptors", "org.apache.kafka.common.utils.Time");
            }

            // Version 2.0.0+ is supported.
            if (constructor == null) {
                constructor = target.getConstructor("org.apache.kafka.clients.producer.ProducerConfig",
                        "org.apache.kafka.common.serialization.Serializer", "org.apache.kafka.common.serialization.Serializer",
                        "org.apache.kafka.clients.Metadata", "org.apache.kafka.clients.KafkaClient");
            }

            if (constructor == null) {
                constructor = target.getConstructor("org.apache.kafka.clients.producer.ProducerConfig",
                        "org.apache.kafka.common.serialization.Serializer", "org.apache.kafka.common.serialization.Serializer");
            }

            if (constructor != null) {
                constructor.addInterceptor(ProducerConstructorInterceptor.class);
            }

            InstrumentMethod sendMethod = target.getDeclaredMethod("send", "org.apache.kafka.clients.producer.ProducerRecord", "org.apache.kafka.clients.producer.Callback");
            if (sendMethod != null) {
                sendMethod.addInterceptor(ProducerSendInterceptor.class);
            }

            // Version 0.11.0+ is supported.
            InstrumentMethod setReadOnlyMethod = target.getDeclaredMethod("setReadOnly", "org.apache.kafka.common.header.Headers");
            if (setReadOnlyMethod != null) {
                setReadOnlyMethod.addInterceptor(ProducerAddHeaderInterceptor.class);
            }
            if (target.hasField("apiVersions")) {
                target.addGetter(ApiVersionsGetter.class, "apiVersions");
            }

            target.addField(RemoteAddressFieldAccessor.class);

            return target.toBytecode();
        }

    }

    public static class TransactionManagerTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            InstrumentMethod beginTransactionMethod = target.getDeclaredMethod("beginTransaction");
            if (beginTransactionMethod != null) {
                beginTransactionMethod.addInterceptor(BasicMethodInterceptor.class, va(KafkaConstants.KAFKA_CLIENT_INTERNAL));
            }

            InstrumentMethod beginCommitMethod = target.getDeclaredMethod("beginCommit");
            if (beginCommitMethod != null) {
                beginCommitMethod.addInterceptor(BasicMethodInterceptor.class, va(KafkaConstants.KAFKA_CLIENT_INTERNAL));
            }

            InstrumentMethod beginAbortMethod = target.getDeclaredMethod("beginAbort");
            if (beginAbortMethod != null) {
                beginAbortMethod.addInterceptor(BasicMethodInterceptor.class, va(KafkaConstants.KAFKA_CLIENT_INTERNAL));
            }

            return target.toBytecode();
        }

    }

    public static class KafkaConsumerTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            InstrumentMethod constructor = target.getConstructor("org.apache.kafka.clients.consumer.ConsumerConfig",
                    "org.apache.kafka.common.serialization.Deserializer", "org.apache.kafka.common.serialization.Deserializer");
            if (constructor != null) {
                constructor.addInterceptor(ConsumerConstructorInterceptor.class);
            }

            if (constructor == null) {
                constructor = target.getConstructor("java.util.Map", "org.apache.kafka.common.serialization.Deserializer", "org.apache.kafka.common.serialization.Deserializer");
                if (constructor != null) {
                    constructor.addInterceptor(ConsumerConstructor_V_2_7_Interceptor.class);
                }
            }

            InstrumentMethod pollLongMethod = target.getDeclaredMethod("poll", "long");
            if (pollLongMethod != null) {
                pollLongMethod.addScopedInterceptor(ConsumerPollInterceptor.class, SCOPE_KAFKA_CONSUMER_POLL);
            }
            InstrumentMethod pollDurationMethod = target.getDeclaredMethod("poll", "java.time.Duration");
            if (pollDurationMethod != null) {
                pollDurationMethod.addScopedInterceptor(ConsumerPollInterceptor.class, SCOPE_KAFKA_CONSUMER_POLL);
            }

            target.addField(RemoteAddressFieldAccessor.class);

            return target.toBytecode();
        }

    }

    public static class ConsumerRecordTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);
            target.addField(RemoteAddressFieldAccessor.class);
            target.addField(EndPointFieldAccessor.class);

            return target.toBytecode();
        }

    }

    public static class ListenerConsumerTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            final InstrumentMethod doInvokeRecordListenerMethod = target.getDeclaredMethod("doInvokeRecordListener", "org.apache.kafka.clients.consumer.ConsumerRecord", "java.util.Iterator");
            if (doInvokeRecordListenerMethod != null) {
                doInvokeRecordListenerMethod.addScopedInterceptor(ConsumerRecordEntryPointInterceptor.class, va(0), KafkaConstants.SCOPE, ExecutionPolicy.BOUNDARY);
            }
            final InstrumentMethod invokeErrorHandlerMethod = target.getDeclaredMethod("invokeErrorHandler", "org.apache.kafka.clients.consumer.ConsumerRecord", "java.util.Iterator", "java.lang.RuntimeException");
            if (invokeErrorHandlerMethod != null) {
                invokeErrorHandlerMethod.addInterceptor(ListenerConsumerInvokeErrorHandlerInterceptor.class);
            }
            final InstrumentMethod doInvokeBatchListenerMethod = target.getDeclaredMethod("doInvokeBatchListener", "org.apache.kafka.clients.consumer.ConsumerRecord", "java.util.List");
            if (doInvokeBatchListenerMethod != null) {
                doInvokeBatchListenerMethod.addScopedInterceptor(ConsumerMultiRecordEntryPointInterceptor.class, va(1), KafkaConstants.SCOPE, ExecutionPolicy.BOUNDARY);
            }
            final InstrumentMethod invokeBatchErrorHandlerMethod = target.getDeclaredMethod("invokeBatchErrorHandler", "org.apache.kafka.clients.consumer.ConsumerRecord", "java.util.List", "java.lang.RuntimeException");
            if (invokeBatchErrorHandlerMethod != null) {
                invokeBatchErrorHandlerMethod.addInterceptor(ListenerConsumerInvokeErrorHandlerInterceptor.class);
            }

            return target.toBytecode();
        }
    }


    public static class AcknowledgingConsumerAwareMessageListenerTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            MethodFilter methodFilter = MethodFilters.chain(MethodFilters.name("onMessage"), MethodFilters.argAt(0, "org.apache.kafka.clients.consumer.ConsumerRecord"));
            List<InstrumentMethod> declaredMethods = target.getDeclaredMethods(methodFilter);
            for (InstrumentMethod declaredMethod : declaredMethods) {
                declaredMethod.addScopedInterceptor(ConsumerRecordEntryPointInterceptor.class, va(0), KafkaConstants.SCOPE, ExecutionPolicy.BOUNDARY);
            }

            return target.toBytecode();
        }

    }

    public static class BatchMessagingMessageListenerAdapterTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            MethodFilter methodFilter = MethodFilters.chain(MethodFilters.name("onMessage"), MethodFilters.argAt(0, "org.apache.kafka.clients.consumer.ConsumerRecords"));
            List<InstrumentMethod> declaredMethods = target.getDeclaredMethods(methodFilter);
            for (InstrumentMethod declaredMethod : declaredMethods) {
                declaredMethod.addScopedInterceptor(ConsumerMultiRecordEntryPointInterceptor.class, va(0), KafkaConstants.SCOPE, ExecutionPolicy.BOUNDARY);
            }

            methodFilter = MethodFilters.chain(MethodFilters.name("onMessage"), MethodFilters.argAt(0, "java.util.List"));
            declaredMethods = target.getDeclaredMethods(methodFilter);
            for (InstrumentMethod declaredMethod : declaredMethods) {
                declaredMethod.addScopedInterceptor(ConsumerMultiRecordEntryPointInterceptor.class, va(0), KafkaConstants.SCOPE, ExecutionPolicy.BOUNDARY);
            }

            return target.toBytecode();
        }

    }

    private boolean enableConsumerTransform(KafkaConfig config) {
        if (config.isConsumerEnable() && StringUtils.hasText(config.getKafkaEntryPoint())) {
            return true;
        }

        return config.isSpringConsumerEnable();
    }

    @Override
    public void setTransformTemplate(TransformTemplate transformTemplate) {
        this.transformTemplate = transformTemplate;
    }

    public void transformEntryPoint(String entryPoint) {
        final String clazzName = toClassName(entryPoint);

        transformTemplate.transform(clazzName, EntryPointTransform.class);
    }

    public static class EntryPointTransform implements TransformCallback {
        private final PluginLogger logger = PluginLogManager.getLogger(this.getClass());

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            final KafkaConfig config = new KafkaConfig(instrumentor.getProfilerConfig());
            final String methodName = toMethodName(config.getKafkaEntryPoint());
            for (InstrumentMethod method : target.getDeclaredMethods(MethodFilters.name(methodName))) {
                try {
                    String[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes == null) {
                        continue;
                    }

                    for (int i = 0; i < parameterTypes.length; i++) {
                        String parameterType = parameterTypes[i];

                        if (KafkaConstants.CONSUMER_RECORD_CLASS_NAME.equals(parameterType)) {
                            method.addInterceptor(ConsumerRecordEntryPointInterceptor.class, va(i));
                            break;
                        } else if (KafkaConstants.CONSUMER_MULTI_RECORD_CLASS_NAME.equals(parameterType)) {
                            method.addInterceptor(ConsumerMultiRecordEntryPointInterceptor.class, va(i));
                            break;
                        }
                    }
                } catch (Exception e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Unsupported method " + method, e);
                    }
                }
            }
            return target.toBytecode();
        }

        private String toMethodName(String fullQualifiedMethodName) {
            final int methodBeginPosition = fullQualifiedMethodName.lastIndexOf('.');
            if (methodBeginPosition <= 0 || methodBeginPosition + 1 >= fullQualifiedMethodName.length()) {
                throw new IllegalArgumentException("invalid full qualified method name(" + fullQualifiedMethodName + "). not found method");
            }

            return fullQualifiedMethodName.substring(methodBeginPosition + 1);
        }

    }

    private String toClassName(String fullQualifiedMethodName) {
        final int classEndPosition = fullQualifiedMethodName.lastIndexOf('.');
        if (classEndPosition <= 0) {
            throw new IllegalArgumentException("invalid full qualified method name(" + fullQualifiedMethodName + "). not found method");
        }

        return fullQualifiedMethodName.substring(0, classEndPosition);
    }

    public static class KafkaSelectorTransform implements TransformCallback {

        private static final String[][] SELECTOR_CLOSE_METHOD_PARAMS = {
                // for v1.1.0+
                {"org.apache.kafka.common.network.KafkaChannel", "org.apache.kafka.common.network.Selector$CloseMode"},
                // for v1.0.1 ~ 1.0.2
                {"org.apache.kafka.common.network.KafkaChannel", "boolean"},
                // for ~ v1.0.0            // for
                {"org.apache.kafka.common.network.KafkaChannel", "boolean", "boolean"}
        };

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            // for v1.0.0+
            InstrumentMethod registerMethod = target.getDeclaredMethod("registerChannel", "java.lang.String", "java.nio.channels.SocketChannel", "int");
            if (registerMethod == null) {
                // for v1.0.0
                registerMethod = target.getDeclaredMethod("buildChannel", "java.nio.channels.SocketChannel", "java.lang.String", "java.nio.channels.SelectionKey");
            }

            if (registerMethod != null) {
                registerMethod.addInterceptor(SocketChannelRegisterInterceptor.class);

                target.addField(SocketChannelListFieldAccessor.class);
            }

            InstrumentMethod closeMethod = null;
            for (String[] selectorCloseMethodParam : SELECTOR_CLOSE_METHOD_PARAMS) {
                closeMethod = target.getDeclaredMethod("close", selectorCloseMethodParam);
                if (closeMethod != null) {
                    break;
                }
            }

            if (closeMethod != null) {
                closeMethod.addInterceptor(SocketChannelCloseInterceptor.class);
            }

            InstrumentMethod doCloseMethod = target.getDeclaredMethod("close", "org.apache.kafka.common.network.KafkaChannel", "boolean");
            if (doCloseMethod != null) {
                doCloseMethod.addInterceptor(SocketChannelCloseInterceptor.class);
            }

            return target.toBytecode();
        }
    }

    public static class NetworkClientTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            // poll(long timeout, long now)
            InstrumentMethod pollMethod = target.getDeclaredMethod("poll", "long", "long");
            if (pollMethod != null) {
                pollMethod.addInterceptor(NetworkClientPollInterceptor.class);
                if (target.hasField("selector")) {
                    target.addGetter(SelectorGetter.class, "selector");
                }
            }

            return target.toBytecode();
        }
    }

    public static class TopicPartitionTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            target.addField(EndPointFieldAccessor.class);

            return target.toBytecode();
        }

    }

    public static class ConsumerRecordsTransform implements TransformCallback {

        @Override
        public byte[] doInTransform(Instrumentor instrumentor, ClassLoader classLoader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws InstrumentException {
            final InstrumentClass target = instrumentor.getInstrumentClass(classLoader, className, classfileBuffer);

            InstrumentMethod constructor = target.getConstructor("java.util.Map");
            if (constructor != null) {
                constructor.addInterceptor(ConsumerRecordsInterceptor.class);
            }
            return target.toBytecode();
        }

    }

}
