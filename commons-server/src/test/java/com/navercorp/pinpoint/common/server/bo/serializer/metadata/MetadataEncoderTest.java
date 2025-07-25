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

package com.navercorp.pinpoint.common.server.bo.serializer.metadata;

import com.navercorp.pinpoint.common.hbase.wd.ByteSaltKey;
import com.navercorp.pinpoint.common.hbase.wd.OneByteSimpleHash;
import com.navercorp.pinpoint.common.hbase.wd.RowKeyDistributorByHashPrefix;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MetadataEncoderTest {
    private final MetadataDecoder decoder = new MetadataDecoder();
    private final MetadataEncoder encoder = new MetadataEncoder(new RowKeyDistributorByHashPrefix(new OneByteSimpleHash(16)));

    @Test
    public void encodeRowKey() {
        long startTime = System.currentTimeMillis();
        MetaDataRowKey metaData = new DefaultMetaDataRowKey("agent", startTime, 1);
        byte[] rowKey = encoder.encodeRowKey(ByteSaltKey.NONE.size(), metaData);
        MetaDataRowKey decodeRowKey = decoder.decodeRowKey(rowKey);

        Assertions.assertEquals("agent", decodeRowKey.getAgentId());
        Assertions.assertEquals(startTime, decodeRowKey.getAgentStartTime());
        Assertions.assertEquals(1, decodeRowKey.getId());
    }
}