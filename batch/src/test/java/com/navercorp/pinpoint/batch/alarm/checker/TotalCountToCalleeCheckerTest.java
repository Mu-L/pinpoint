/*
 * Copyright 2014 NAVER Corp.
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

package com.navercorp.pinpoint.batch.alarm.checker;

import com.navercorp.pinpoint.batch.alarm.collector.MapOutLinkDataCollector;
import com.navercorp.pinpoint.common.timeseries.window.TimeWindow;
import com.navercorp.pinpoint.common.trace.ServiceType;
import com.navercorp.pinpoint.web.alarm.CheckerCategory;
import com.navercorp.pinpoint.web.alarm.DataCollectorCategory;
import com.navercorp.pinpoint.web.alarm.vo.Rule;
import com.navercorp.pinpoint.web.applicationmap.dao.MapOutLinkDao;
import com.navercorp.pinpoint.web.applicationmap.histogram.TimeHistogram;
import com.navercorp.pinpoint.web.applicationmap.rawdata.LinkCallDataMap;
import com.navercorp.pinpoint.web.applicationmap.rawdata.LinkData;
import com.navercorp.pinpoint.web.applicationmap.rawdata.LinkDataMap;
import com.navercorp.pinpoint.web.vo.Application;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TotalCountToCalleeCheckerTest {

    private static final String FROM_SERVICE_NAME = "from_local_service";
    private static final String TO_SERVICE_NAME = "to_local_service";
    private static final String SERVICE_TYPE = "tomcat";
    public static MapOutLinkDao dao;

    @BeforeAll
    public static void before() {
        dao = new MapOutLinkDao() {

            @Override
            public LinkDataMap selectOutLink(Application outApplication, TimeWindow range, boolean timeAggregated) {
                long timeStamp = 1409814914298L;
                LinkDataMap linkDataMap = new LinkDataMap();
                Application fromApplication = new Application(FROM_SERVICE_NAME, ServiceType.STAND_ALONE);
                for (int i = 1; i < 6; i++) {
                    LinkCallDataMap linkCallDataMap = new LinkCallDataMap();
                    Application toApplication = new Application(TO_SERVICE_NAME + i, ServiceType.STAND_ALONE);
                    Collection<TimeHistogram> timeHistogramList = new ArrayList<>();

                    for (int j = 1; j < 11; j++) {
                        TimeHistogram timeHistogram = new TimeHistogram(ServiceType.STAND_ALONE, timeStamp);
                        timeHistogram.addCallCountByElapsedTime(i * j * 1000, false);
                        timeHistogramList.add(timeHistogram);
                    }

                    linkCallDataMap.addCallData(fromApplication, toApplication, timeHistogramList);
                    LinkData linkData = LinkData.copyOf(fromApplication, toApplication, linkCallDataMap);
                    linkDataMap.addLinkData(linkData);
                }

                return linkDataMap;
            }
        };
    }

    @Test
    public void checkTest() {
        Application application = new Application(FROM_SERVICE_NAME, ServiceType.STAND_ALONE);
        MapOutLinkDataCollector dataCollector = new MapOutLinkDataCollector(DataCollectorCategory.CALLER_STAT, application, dao, System.currentTimeMillis(), 300000);
        Rule rule = new Rule(FROM_SERVICE_NAME, SERVICE_TYPE, CheckerCategory.TOTAL_COUNT_TO_CALLEE.getName(), 10, "testGroup", false, false, false, TO_SERVICE_NAME + 1);
        TotalCountToCalleeChecker checker = new TotalCountToCalleeChecker(dataCollector, rule);

        checker.check();
        assertTrue(checker.isDetected());
    }

    @Test
    public void checkTest2() {
        Application application = new Application(FROM_SERVICE_NAME, ServiceType.STAND_ALONE);
        MapOutLinkDataCollector dataCollector = new MapOutLinkDataCollector(DataCollectorCategory.CALLER_STAT, application, dao, System.currentTimeMillis(), 300000);
        Rule rule = new Rule(FROM_SERVICE_NAME, SERVICE_TYPE, CheckerCategory.TOTAL_COUNT_TO_CALLEE.getName(), 11, "testGroup", false, false, false, TO_SERVICE_NAME + 1);
        TotalCountToCalleeChecker checker = new TotalCountToCalleeChecker(dataCollector, rule);

        checker.check();
        assertFalse(checker.isDetected());
    }

    @Test
    public void checkTest3() {
        Application application = new Application(FROM_SERVICE_NAME, ServiceType.STAND_ALONE);
        MapOutLinkDataCollector dataCollector = new MapOutLinkDataCollector(DataCollectorCategory.CALLER_STAT, application, dao, System.currentTimeMillis(), 300000);
        Rule rule = new Rule(FROM_SERVICE_NAME, SERVICE_TYPE, CheckerCategory.TOTAL_COUNT_TO_CALLEE.getName(), 10, "testGroup", false, false, false, TO_SERVICE_NAME + 2);
        TotalCountToCalleeChecker checker = new TotalCountToCalleeChecker(dataCollector, rule);

        checker.check();
        assertTrue(checker.isDetected());
    }
}
