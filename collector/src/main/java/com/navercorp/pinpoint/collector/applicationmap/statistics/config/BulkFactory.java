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

package com.navercorp.pinpoint.collector.applicationmap.statistics.config;

import com.navercorp.pinpoint.collector.applicationmap.statistics.BulkIncrementer;
import com.navercorp.pinpoint.collector.applicationmap.statistics.BulkUpdater;
import com.navercorp.pinpoint.collector.applicationmap.statistics.BulkWriter;
import com.navercorp.pinpoint.collector.applicationmap.statistics.DefaultBulkIncrementer;
import com.navercorp.pinpoint.collector.applicationmap.statistics.DefaultBulkUpdater;
import com.navercorp.pinpoint.collector.applicationmap.statistics.DefaultBulkWriter;
import com.navercorp.pinpoint.collector.applicationmap.statistics.RowKeyMerge;
import com.navercorp.pinpoint.collector.applicationmap.statistics.SyncWriter;
import com.navercorp.pinpoint.collector.monitor.dao.hbase.BulkOperationReporter;
import com.navercorp.pinpoint.common.hbase.HbaseColumnFamily;
import com.navercorp.pinpoint.common.hbase.TableNameProvider;
import com.navercorp.pinpoint.common.hbase.async.HbaseAsyncTemplate;
import com.navercorp.pinpoint.common.hbase.wd.RowKeyDistributorByHashPrefix;

import java.util.Objects;

/**
 * @author emeroad
 */
public class BulkFactory {

    private final BulkProperties bulkProperties;
    private final BulkIncrementerFactory bulkIncrementerFactory;
    private final BulkOperationReporterFactory bulkOperationReporterFactory;

    public BulkFactory(BulkProperties bulkProperties,
                       BulkIncrementerFactory bulkIncrementerFactory,
                       BulkOperationReporterFactory bulkOperationReporterFactory) {
        this.bulkProperties = Objects.requireNonNull(bulkProperties, "bulkConfiguration");
        this.bulkIncrementerFactory = Objects.requireNonNull(bulkIncrementerFactory, "bulkIncrementerFactory");
        this.bulkOperationReporterFactory = Objects.requireNonNull(bulkOperationReporterFactory, "bulkOperationReporterFactory");
    }


    public BulkIncrementer newBulkIncrementer(String reporterName, HbaseColumnFamily hbaseColumnFamily, int limitSize) {
        BulkOperationReporter reporter = bulkOperationReporterFactory.getBulkOperationReporter(reporterName);
        RowKeyMerge merge = new RowKeyMerge(hbaseColumnFamily);
        BulkIncrementer bulkIncrementer = new DefaultBulkIncrementer(merge);

        return bulkIncrementerFactory.wrap(bulkIncrementer, limitSize, reporter);
    }


    public BulkUpdater getBulkUpdater(String reporterName) {
        BulkOperationReporter reporter = bulkOperationReporterFactory.getBulkOperationReporter(reporterName);
        BulkUpdater bulkUpdater = new DefaultBulkUpdater();
        return bulkIncrementerFactory.wrap(bulkUpdater, bulkProperties.getCalleeLimitSize(), reporter);
    }

    public BulkWriter newBulkWriter(String loggerName,
                                     HbaseAsyncTemplate asyncTemplate,
                                     HbaseColumnFamily descriptor,
                                     TableNameProvider tableNameProvider,
                                     RowKeyDistributorByHashPrefix rowKeyDistributorByHashPrefix,
                                     BulkIncrementer bulkIncrementer,
                                     BulkUpdater bulkUpdater) {
        if (bulkProperties.enableBulk()) {
            return new DefaultBulkWriter(loggerName, asyncTemplate, rowKeyDistributorByHashPrefix,
                    bulkIncrementer, bulkUpdater, descriptor, tableNameProvider);
        } else {
            return new SyncWriter(loggerName, asyncTemplate, rowKeyDistributorByHashPrefix, descriptor, tableNameProvider);
        }
    }

}
