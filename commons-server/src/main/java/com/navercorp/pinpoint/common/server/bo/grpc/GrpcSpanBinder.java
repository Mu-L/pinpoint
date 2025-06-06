/*
 * Copyright 2019 NAVER Corp.
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

package com.navercorp.pinpoint.common.server.bo.grpc;


import com.navercorp.pinpoint.common.profiler.util.TransactionId;
import com.navercorp.pinpoint.common.server.bo.AnnotationBo;
import com.navercorp.pinpoint.common.server.bo.AnnotationComparator;
import com.navercorp.pinpoint.common.server.bo.AnnotationFactory;
import com.navercorp.pinpoint.common.server.bo.ExceptionInfo;
import com.navercorp.pinpoint.common.server.bo.LocalAsyncIdBo;
import com.navercorp.pinpoint.common.server.bo.SpanBo;
import com.navercorp.pinpoint.common.server.bo.SpanChunkBo;
import com.navercorp.pinpoint.common.server.bo.SpanEventBo;
import com.navercorp.pinpoint.common.server.bo.SpanEventComparator;
import com.navercorp.pinpoint.common.util.IdValidateUtils;
import com.navercorp.pinpoint.common.util.StringUtils;
import com.navercorp.pinpoint.grpc.MessageFormatUtils;
import com.navercorp.pinpoint.grpc.trace.PAcceptEvent;
import com.navercorp.pinpoint.grpc.trace.PAnnotation;
import com.navercorp.pinpoint.grpc.trace.PIntStringValue;
import com.navercorp.pinpoint.grpc.trace.PLocalAsyncId;
import com.navercorp.pinpoint.grpc.trace.PMessageEvent;
import com.navercorp.pinpoint.grpc.trace.PNextEvent;
import com.navercorp.pinpoint.grpc.trace.PParentInfo;
import com.navercorp.pinpoint.grpc.trace.PSpan;
import com.navercorp.pinpoint.grpc.trace.PSpanChunk;
import com.navercorp.pinpoint.grpc.trace.PSpanEvent;
import com.navercorp.pinpoint.grpc.trace.PTransactionId;
import com.navercorp.pinpoint.io.SpanVersion;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @author Woonduk Kang(emeroad)
 */
public class GrpcSpanBinder {

    private final Logger logger = LogManager.getLogger(this.getClass());

    private static final AnnotationFactory<PAnnotation> annotationFactory = new AnnotationFactory<>(new GrpcAnnotationHandler());


    public GrpcSpanBinder() {
    }


    public SpanBo bindSpanBo(PSpan pSpan, BindAttribute attribute) {
        checkVersion(pSpan.getVersion());

        return newSpanBo(pSpan, attribute);
    }

    private void checkVersion(int version) {
        if (version != SpanVersion.TRACE_V2) {
            throw new IllegalStateException("unsupported version:" + version);
        }
    }

    // for test
    SpanBo newSpanBo(PSpan pSpan, BindAttribute attribute) {
        final SpanBo spanBo = new SpanBo();
        spanBo.setVersion(pSpan.getVersion());
        spanBo.setAgentId(attribute.getAgentId());
        spanBo.setApplicationName(attribute.getApplicationName());
        spanBo.setAgentName(attribute.getAgentName());
        spanBo.setAgentStartTime(attribute.getAgentStartTime());
        spanBo.setCollectorAcceptTime(attribute.getAcceptedTime());

        if (!pSpan.hasTransactionId()) {
            throw new IllegalStateException("hasTransactionId() is false " + MessageFormatUtils.debugLog(pSpan));
        }
        final TransactionId transactionId = newTransactionId(pSpan.getTransactionId(), spanBo.getAgentId());
        spanBo.setTransactionId(transactionId);

        spanBo.setSpanId(pSpan.getSpanId());
        spanBo.setParentSpanId(pSpan.getParentSpanId());

        spanBo.setStartTime(pSpan.getStartTime());
        spanBo.setElapsed(pSpan.getElapsed());

        spanBo.setServiceType(pSpan.getServiceType());

        spanBo.setFlag((short) pSpan.getFlag());
        spanBo.setApiId(pSpan.getApiId());

        spanBo.setErrCode(pSpan.getErr());

        spanBo.setLoggingTransactionInfo((byte) pSpan.getLoggingTransactionInfo());

        spanBo.setApplicationServiceType(pSpan.getApplicationServiceType());

        if (pSpan.hasAcceptEvent()) {
            final PAcceptEvent acceptEvent = pSpan.getAcceptEvent();
            final String rpc = acceptEvent.getRpc();
            if (StringUtils.hasLength(rpc)) {
                spanBo.setRpc(rpc);
            }

            final String remoteAddr = acceptEvent.getRemoteAddr();
            if (StringUtils.hasLength(remoteAddr)) {
                spanBo.setRemoteAddr(remoteAddr);
            }

            final String endPoint = acceptEvent.getEndPoint();
            if (StringUtils.hasLength(endPoint)) {
                spanBo.setEndPoint(endPoint);
            }

            if (acceptEvent.hasParentInfo()) {
                final PParentInfo parentInfo = acceptEvent.getParentInfo();

                final String acceptorHost = parentInfo.getAcceptorHost();
                if (StringUtils.hasLength(acceptorHost)) {
                    spanBo.setAcceptorHost(acceptorHost);
                }

                final String parentApplicationName = parentInfo.getParentApplicationName();
                // If root node, parentApplicationName is null
                if (StringUtils.hasLength(parentApplicationName)) {
                    if (!IdValidateUtils.validateId(parentApplicationName)) {
                        throw new IllegalArgumentException("Invalid parentApplicationName " + parentApplicationName
                                + " agent:" + attribute.getApplicationName() + "/" + attribute.getAgentId());
                    }
                    spanBo.setParentApplicationName(parentApplicationName);
                    spanBo.setParentApplicationServiceType((short) parentInfo.getParentApplicationType());
                }
            }
        }

        // FIXME span.errCode contains error of span and spanEvent
        // because exceptionInfo is the error information of span itself, exceptionInfo can be null even if errCode is not 0
        if (pSpan.hasExceptionInfo()) {
            final PIntStringValue pException = pSpan.getExceptionInfo();
            ExceptionInfo exceptionInfo = new ExceptionInfo(pException.getIntValue(), getExceptionMessage(pException));
            spanBo.setExceptionInfo(exceptionInfo);
        }

        List<AnnotationBo> annotationBoList = buildAnnotationList(pSpan.getAnnotationList());
        spanBo.setAnnotationBoList(annotationBoList);

        return spanBo;
    }


    private String getExceptionMessage(PIntStringValue exceptionInfo) {
        if (exceptionInfo.hasStringValue()) {
            return exceptionInfo.getStringValue().getValue();
        }
        return null;
    }


    private void bind(SpanEventBo spanEvent, PSpanEvent pSpanEvent, SpanEventBo prevSpanEvent) {

        spanEvent.setSequence((short) pSpanEvent.getSequence());

        int startTime = getStartTimeDelta(pSpanEvent, prevSpanEvent);
        spanEvent.setStartElapsed(startTime);
        spanEvent.setEndElapsed(pSpanEvent.getEndElapsed());

        spanEvent.setServiceType((short) pSpanEvent.getServiceType());

        spanEvent.setApiId(pSpanEvent.getApiId());

        // v2 spec
        int depth = getDepthDelta(pSpanEvent, prevSpanEvent);
        spanEvent.setDepth(depth);

        if (pSpanEvent.hasNextEvent()) {
            final PNextEvent nextEvent = pSpanEvent.getNextEvent();
            final PNextEvent.FieldCase fieldCase = nextEvent.getFieldCase();
            if (fieldCase == PNextEvent.FieldCase.MESSAGEEVENT) {
                final PMessageEvent messageEvent = nextEvent.getMessageEvent();

                spanEvent.setNextSpanId(messageEvent.getNextSpanId());

                final String destinationId = messageEvent.getDestinationId();
                if (StringUtils.hasLength(destinationId)) {
                    spanEvent.setDestinationId(destinationId);
                }

                final String endPoint = messageEvent.getEndPoint();
                if (StringUtils.hasLength(endPoint)) {
                    spanEvent.setEndPoint(endPoint);
                }
            } else {
                logger.info("unknown nextEvent:{}", nextEvent);
            }
        }
        final int asyncEvent = pSpanEvent.getAsyncEvent();
        spanEvent.setNextAsyncId(asyncEvent);

        List<AnnotationBo> annotationList = buildAnnotationList(pSpanEvent.getAnnotationList());
        spanEvent.setAnnotationBoList(annotationList);

        if (pSpanEvent.hasExceptionInfo()) {
            final PIntStringValue pException = pSpanEvent.getExceptionInfo();
            ExceptionInfo exceptionInfo = new ExceptionInfo(pSpanEvent.getExceptionInfo().getIntValue(), getExceptionMessage(pException));
            spanEvent.setExceptionInfo(exceptionInfo);
        }
    }

    private int getDepthDelta(PSpanEvent pSpanEvent, SpanEventBo prevSpanEvent) {
        final int depth = pSpanEvent.getDepth();
        if (depth == 0) {
            // depth compact case
            if (prevSpanEvent == null) {
                // first spanEvent
                return 0;
            } else {
                return prevSpanEvent.getDepth();
            }
        } else {
            return depth;
        }
    }

    private int getStartTimeDelta(PSpanEvent pSpanEvent, SpanEventBo prevSpanEvent) {
        if (prevSpanEvent == null) {
            return pSpanEvent.getStartElapsed();
        } else {
            return pSpanEvent.getStartElapsed() + prevSpanEvent.getStartElapsed();
        }
    }

    public SpanChunkBo bindSpanChunkBo(PSpanChunk pSpanChunk, BindAttribute attribute) {
        checkVersion(pSpanChunk.getVersion());

        final SpanChunkBo spanChunkBo = newSpanChunkBo(pSpanChunk, attribute);
        if (pSpanChunk.hasLocalAsyncId()) {
            final PLocalAsyncId pLocalAsyncId = pSpanChunk.getLocalAsyncId();
            LocalAsyncIdBo localAsyncIdBo = new LocalAsyncIdBo(pLocalAsyncId.getAsyncId(), pLocalAsyncId.getSequence());
            spanChunkBo.setLocalAsyncId(localAsyncIdBo);
        }

        return spanChunkBo;
    }


    // for test
    SpanChunkBo newSpanChunkBo(PSpanChunk pSpanChunk, BindAttribute attribute) {
        final SpanChunkBo spanChunkBo = new SpanChunkBo();
        spanChunkBo.setVersion(pSpanChunk.getVersion());
        spanChunkBo.setAgentId(attribute.getAgentId());
        spanChunkBo.setApplicationName(attribute.getApplicationName());
        spanChunkBo.setAgentStartTime(attribute.getAgentStartTime());
        spanChunkBo.setCollectorAcceptTime(attribute.getAcceptedTime());

        spanChunkBo.setApplicationServiceType((short) pSpanChunk.getApplicationServiceType());

        if (pSpanChunk.hasTransactionId()) {
            PTransactionId pTransactionId = pSpanChunk.getTransactionId();
            TransactionId transactionId = newTransactionId(pTransactionId, spanChunkBo.getAgentId());
            spanChunkBo.setTransactionId(transactionId);
        } else {
            logger.warn("PTransactionId is not set {}", pSpanChunk);
            throw new IllegalStateException("PTransactionId is not set");
        }

        spanChunkBo.setKeyTime(pSpanChunk.getKeyTime());

        spanChunkBo.setSpanId(pSpanChunk.getSpanId());
        spanChunkBo.setEndPoint(pSpanChunk.getEndPoint());
        return spanChunkBo;
    }

    private TransactionId newTransactionId(PTransactionId pTransactionId, String spanAgentId) {
        final String transactionAgentId = pTransactionId.getAgentId();
        if (StringUtils.hasLength(transactionAgentId)) {
            return TransactionId.of(transactionAgentId, pTransactionId.getAgentStartTime(), pTransactionId.getSequence());
        } else {
            return TransactionId.of(spanAgentId, pTransactionId.getAgentStartTime(), pTransactionId.getSequence());
        }
    }


    public List<SpanEventBo> bindSpanEventBoList(List<PSpanEvent> spanEventList) {
        if (CollectionUtils.isEmpty(spanEventList)) {
            return Collections.emptyList();
        }
        List<SpanEventBo> spanEventBoList = new ArrayList<>(spanEventList.size());
        SpanEventBo prevSpanEvent = null;
        for (PSpanEvent pSpanEvent : spanEventList) {
            final SpanEventBo spanEventBo = buildSpanEventBo(pSpanEvent, prevSpanEvent);
            spanEventBoList.add(spanEventBo);
            prevSpanEvent = spanEventBo;
        }

        spanEventBoList.sort(SpanEventComparator.INSTANCE);
        return spanEventBoList;
    }

    private List<AnnotationBo> buildAnnotationList(List<PAnnotation> pAnnotationList) {
        if (CollectionUtils.isEmpty(pAnnotationList)) {
            return Collections.emptyList();
        }
        List<AnnotationBo> boList = new ArrayList<>(pAnnotationList.size());
        for (PAnnotation tAnnotation : pAnnotationList) {
            final AnnotationBo annotationBo = newAnnotationBo(tAnnotation);
            boList.add(annotationBo);
        }

        boList.sort(AnnotationComparator.INSTANCE);
        return boList;
    }

    // for test
    public SpanEventBo buildSpanEventBo(PSpanEvent pSpanEvent, SpanEventBo prevSpanEvent) {
        Objects.requireNonNull(pSpanEvent, "pSpanEvent");

        final SpanEventBo spanEvent = new SpanEventBo();
        bind(spanEvent, pSpanEvent, prevSpanEvent);
        return spanEvent;
    }

    private AnnotationBo newAnnotationBo(PAnnotation pAnnotation) {
        Objects.requireNonNull(pAnnotation, "pAnnotation");

        return annotationFactory.buildAnnotation(pAnnotation);
    }


}
