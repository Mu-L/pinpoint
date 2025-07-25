/*
 * Copyright 2025 NAVER Corp.
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

package com.navercorp.pinpoint.web.mapper;

import com.navercorp.pinpoint.common.buffer.Buffer;
import com.navercorp.pinpoint.common.buffer.FixedBuffer;
import com.navercorp.pinpoint.common.hbase.HbaseTables;
import com.navercorp.pinpoint.common.hbase.RowMapper;
import com.navercorp.pinpoint.common.hbase.wd.RowKeyDistributorByHashPrefix;
import com.navercorp.pinpoint.common.server.bo.ApiMetaDataBo;
import com.navercorp.pinpoint.common.server.bo.MethodTypeEnum;
import com.navercorp.pinpoint.common.server.bo.serializer.RowKeyDecoder;
import com.navercorp.pinpoint.common.server.bo.serializer.metadata.MetaDataRowKey;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.client.Result;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @author emeroad
 * @author minwoo.jung
 */
@Component
public class ApiMetaDataMapper implements RowMapper<List<ApiMetaDataBo>> {

    private final static byte[] API_METADATA_CQ = HbaseTables.API_METADATA_API.QUALIFIER_SIGNATURE;

    private final Logger logger = LogManager.getLogger(this.getClass());

    private final RowKeyDistributorByHashPrefix rowKeyDistributorByHashPrefix;

    private final RowKeyDecoder<MetaDataRowKey> decoder;

    public ApiMetaDataMapper(RowKeyDecoder<MetaDataRowKey> decoder,
                             @Qualifier("metadataRowKeyDistributor")
                             RowKeyDistributorByHashPrefix rowKeyDistributorByHashPrefix) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.rowKeyDistributorByHashPrefix = Objects.requireNonNull(rowKeyDistributorByHashPrefix, "rowKeyDistributorByHashPrefix");
    }

    @Override
    public List<ApiMetaDataBo> mapRow(Result result, int rowNum) throws Exception {
        if (result.isEmpty()) {
            return Collections.emptyList();
        }
        final byte[] rowKey = getOriginalKey(result.getRow());

        final MetaDataRowKey key = decoder.decodeRowKey(rowKey);

        List<ApiMetaDataBo> apiMetaDataList = new ArrayList<>(result.size());

        for (Cell cell : result.rawCells()) {
            final byte[] value = getValue(cell);
            Buffer buffer = new FixedBuffer(value);

            final String apiInfo = buffer.readPrefixedString();
            final int lineNumber = buffer.readInt();
            MethodTypeEnum methodTypeEnum = MethodTypeEnum.DEFAULT;
            if (buffer.hasRemaining()) {
                methodTypeEnum = MethodTypeEnum.valueOf(buffer.readInt());
            }
            String location = null;
            if (buffer.hasRemaining()) {
                location = buffer.readPrefixedString();
            }

            ApiMetaDataBo.Builder builder = new ApiMetaDataBo.Builder(key.getAgentId(), key.getAgentStartTime(), key.getId(), lineNumber, methodTypeEnum, apiInfo);
            if (location != null) {
                builder.setLocation(location);
            }
            ApiMetaDataBo apiMetaDataBo = builder.build();

            apiMetaDataList.add(apiMetaDataBo);
            if (logger.isDebugEnabled()) {
                logger.debug("read apiAnnotation:{}", apiMetaDataBo);
            }
        }
        return apiMetaDataList;
    }

    private byte[] getValue(Cell cell) {
        if (CellUtil.matchingQualifier(cell, API_METADATA_CQ)) {
            return CellUtil.cloneValue(cell);
        } else {
            // backward compatibility
            return CellUtil.cloneQualifier(cell);
        }
    }

    private byte[] getOriginalKey(byte[] rowKey) {
        return rowKeyDistributorByHashPrefix.getOriginalKey(rowKey);
    }


}

