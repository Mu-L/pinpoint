/*
 * Copyright 2021 NAVER Corp.
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

package com.navercorp.pinpoint.metric.collector.service;

import com.navercorp.pinpoint.metric.collector.dao.pinot.PinotSystemMetricDoubleDao;
import com.navercorp.pinpoint.metric.common.model.DoubleMetric;
import com.navercorp.pinpoint.metric.common.model.Metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Hyunjoon Cho
 */
@ExtendWith(MockitoExtension.class)
public class SystemMetricFilterTest {
    private final Random random = new Random();

    @Mock
    private DoubleMetric doubleMetric;
    @Mock
    private PinotSystemMetricDoubleDao doubleDao;

    @Test
    public void testFilter() {
        int doubleCount = random.nextInt(100);
        Metrics systemMetrics = createList(doubleCount);

        List<DoubleMetric> doubleMetricList = systemMetrics.getMetrics();

        assertThat(doubleMetricList).hasSize(doubleCount);
    }

    private Metrics createList(int doubleCount) {
        List<DoubleMetric> systemMetricList = new ArrayList<>();

        for (int i = 0; i < doubleCount; i++) {
            systemMetricList.add(doubleMetric);
        }

        return new Metrics("tenantId", "hostGroupName", "hostName", systemMetricList);
    }
}
