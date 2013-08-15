/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.sling.hc.healthchecks.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.hc.api.Constants;
import org.apache.sling.hc.api.HealthCheck;
import org.apache.sling.hc.api.Result;
import org.apache.sling.hc.api.ResultLog;
import org.apache.sling.hc.healthchecks.util.FormattingResultLog;
import org.apache.sling.hc.util.HealthCheckFilter;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link HealthCheck} that executes a number of other HealthChecks,
 *  selected by their tags, and merges their Results.
 */
@Component(
        name="org.apache.sling.hc.CompositeHealthCheck",
        configurationFactory=true, 
        policy=ConfigurationPolicy.REQUIRE, 
        metatype=true)
@Service
public class CompositeHealthCheck implements HealthCheck {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private Map<String, String> info;
    private BundleContext bundleContext;
    
    @Property(cardinality=50)
    public static final String PROP_TAGS = Constants.HC_TAGS;
    
    @Property(cardinality=50)
    public static final String PROP_FILTER_TAGS = "filter.tags";
    private String [] filterTags;

    @Property
    public static final String PROP_MBEAN_NAME = Constants.HC_MBEAN_NAME;
    
    @Activate
    public void activate(ComponentContext ctx) {
        bundleContext = ctx.getBundleContext();
        info = new HealthCheckInfo(ctx.getProperties());
        filterTags = PropertiesUtil.toStringArray(ctx.getProperties().get(PROP_FILTER_TAGS), new String[] {});
        info.put(PROP_FILTER_TAGS, Arrays.asList(filterTags).toString());
        log.info("Activated, will select HealthCheck having tags {}", Arrays.asList(filterTags));
    }
    
    @Override
    public Result execute() {
        final FormattingResultLog resultLog = new FormattingResultLog();
        final List<HealthCheck> checks = new HealthCheckFilter(bundleContext).getTaggedHealthCheck(filterTags);
        if(checks.size() == 0) {
            resultLog.warn("HealthCheckFilter returns no HealthCheck for tags {}", Arrays.asList(filterTags));
            return new Result(resultLog);
        }

        int executed = 0;
        resultLog.debug("Executing {} HealthCheck selected by the {} tags", checks.size(), Arrays.asList(filterTags));
        int failures = 0;
        for(HealthCheck hc : checks) {
            if(hc == this) {
                resultLog.info("Cowardly forfeiting execution of this HealthCheck in an infinite loop, ignoring it");
                continue;
            }
            resultLog.debug("Executing {}", hc);
            executed++;
            final Result sub = hc.execute();
            if(!sub.isOk()) {
                failures++;
            }
            for(ResultLog.Entry e : sub) {
                resultLog.add(e);
            }
        }
        
        if(failures == 0) {
            resultLog.debug("{} HealthCheck executed, all ok", executed);
        } else {
            resultLog.warn("{} HealthCheck executed, {} failures", executed, failures);
        }
        
        return new Result(resultLog);
    }

    @Override
    public Map<String, String> getInfo() {
        return info;
    }
}