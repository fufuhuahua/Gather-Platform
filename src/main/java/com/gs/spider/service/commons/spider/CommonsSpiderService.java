package com.gs.spider.service.commons.spider;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gs.spider.gather.async.AsyncGather;
import com.gs.spider.gather.async.quartz.QuartzManager;
import com.gs.spider.gather.async.quartz.WebpageSpiderJob;
import com.gs.spider.gather.commons.CommonSpider;
import com.gs.spider.model.commons.SpiderInfo;
import com.gs.spider.model.commons.Webpage;
import com.gs.spider.model.utils.ResultBundle;
import com.gs.spider.model.utils.ResultBundleBuilder;
import com.gs.spider.model.utils.ResultListBundle;
import com.gs.spider.service.AsyncGatherService;
import com.gs.spider.service.commons.spiderinfo.SpiderInfoService;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.management.JMException;
import java.util.List;
import java.util.Map;

/**
 * CommonsSpiderService
 *
 * @author Gao Shen
 * @version 16/4/13
 */
@Component
public class CommonsSpiderService extends AsyncGatherService {
    private final String QUARTZ_JOB_GROUP_NAME = "webpage-spider-job";
    private final String QUARTZ_TRIGGER_GROUP_NAME = "webpage-spider-trigger";
    private final String QUARTZ_TRIGGER_NAME_SUFFIX = "-hours";
    private Logger LOG = LogManager.getLogger(CommonsSpiderService.class);
    @Autowired
    private CommonSpider commonSpider;
    @Autowired
    private ResultBundleBuilder bundleBuilder;
    @Autowired
    private SpiderInfoService spiderInfoService;
    @Autowired
    private QuartzManager quartzManager;
    private Gson gson = new Gson();

    @Autowired
    public CommonsSpiderService(@Qualifier("commonSpider") AsyncGather asyncGather) {
        super(asyncGather);
    }

    /**
     * ????????????
     *
     * @param spiderInfo ??????????????????spiderinfo
     * @return ??????id
     */
    public ResultBundle<String> start(SpiderInfo spiderInfo) {
        //??????id?????????????????????
        if (StringUtils.isBlank(spiderInfo.getId())) {
            validateSpiderInfo(spiderInfo);
            String spiderInfoId = spiderInfoService.index(spiderInfo).getResult();
            spiderInfo.setId(spiderInfoId);
        } else {
            //??????id????????????????????????id???????????????
            spiderInfoService.update(spiderInfo);
        }
        return bundleBuilder.bundle(spiderInfo.toString(), () -> commonSpider.start(spiderInfo));
    }

    /**
     * ????????????
     *
     * @param spiderInfoJson ??????json????????????????????????spiderinfo
     * @return ??????id
     */
    public ResultBundle<String> start(String spiderInfoJson) {
        Preconditions.checkArgument(StringUtils.isNotBlank(spiderInfoJson), "????????????????????????");
        SpiderInfo spiderInfo = gson.fromJson(spiderInfoJson, SpiderInfo.class);
        return start(spiderInfo);
    }

    /**
     * ????????????
     *
     * @param uuid ??????id(??????uuid)
     * @return
     */
    public ResultBundle<String> stop(String uuid) {
        return bundleBuilder.bundle(uuid, () -> {
            commonSpider.stop(uuid);
            return "OK";
        });
    }

    /**
     * ????????????
     *
     * @param uuid ??????uuid ??????id
     * @return
     */
    public ResultBundle<String> delete(String uuid) {
        return bundleBuilder.bundle(uuid, () -> {
            commonSpider.delete(uuid);
            return "OK";
        });
    }

    /**
     * ??????????????????
     *
     * @return
     */
    public ResultBundle<String> deleteAll() {
        return bundleBuilder.bundle(null, () -> {
            commonSpider.deleteAll();
            return "OK";
        });
    }

    /**
     * ???????????????????????????
     *
     * @param uuid ??????uuid ??????id
     * @return
     */
    public ResultBundle<Map<Object, Object>> runtimeInfo(String uuid, boolean containsExtraInfo) {
        return bundleBuilder.bundle(uuid, () -> commonSpider.getSpiderRuntimeInfo(uuid, containsExtraInfo));
    }

    /**
     * ????????????????????????????????????
     *
     * @return
     */
    public ResultBundle<Map<String, Map<Object, Object>>> list(boolean containsExtraInfo) {
        return bundleBuilder.bundle(null, () -> commonSpider.listAllSpiders(containsExtraInfo));
    }

    /**
     * ??????????????????
     *
     * @param spiderInfoJson
     * @return
     */
    public ResultListBundle<Webpage> testSpiderInfo(String spiderInfoJson) {
        SpiderInfo spiderInfo = gson.fromJson(spiderInfoJson, SpiderInfo.class);
        validateSpiderInfo(spiderInfo);
        return bundleBuilder.listBundle(spiderInfoJson, () -> commonSpider.testSpiderInfo(spiderInfo));
    }

    /**
     * ????????????url?????????
     *
     * @return
     */
    public ResultListBundle<String> getIgnoredUrls() {
        return bundleBuilder.listBundle(null, () -> commonSpider.getIgnoredUrls());
    }

    /**
     * ????????????url?????????
     *
     * @param postfix
     */
    public ResultBundle<String> addIgnoredUrl(String postfix) {
        return bundleBuilder.bundle(postfix, () -> {
            commonSpider.addIgnoredUrl(postfix);
            return "OK";
        });
    }

    /**
     * ??????????????????
     *
     * @param spiderInfo ????????????
     */
    private void validateSpiderInfo(SpiderInfo spiderInfo) {
        Preconditions.checkArgument(spiderInfo.getStartURL().size() > 0, "??????????????????????????????");
        Preconditions.checkArgument(StringUtils.isNotBlank(spiderInfo.getDomain()), "domain????????????");
        Preconditions.checkArgument(!spiderInfo.getDomain().contains("/"), "??????????????????/");
        Preconditions.checkArgument(spiderInfo.getThread() > 0, "?????????????????????0");
        Preconditions.checkArgument(StringUtils.isNotBlank(spiderInfo.getSiteName()), "????????????????????????");
        Preconditions.checkArgument(spiderInfo.getTimeout() > 1000, "????????????????????????1???");
        if (spiderInfo.getDynamicFields() != null) {
            Preconditions.checkArgument(
                    //?????????????????????????????????name,???????????????xpath??????????????????
                    spiderInfo.getDynamicFields().stream()
                            .filter(fieldConfig ->
                                    StringUtils.isBlank(fieldConfig.getName()) ||
                                            (StringUtils.isBlank(fieldConfig.getRegex()) && StringUtils.isBlank(fieldConfig.getXpath()))
                            )
                            .count() == 0,
                    "????????????????????????????????????,?????????????????????????????????name,???????????????xpath??????????????????,?????????");
        }
    }

    /**
     * ??????????????????ID??????????????????
     *
     * @param spiderInfoIdList ????????????ID??????
     * @return ??????id??????
     */
    public ResultListBundle<String> startAll(List<String> spiderInfoIdList) {
        return bundleBuilder.listBundle(spiderInfoIdList.toString(), () -> {
            List<String> taskIdList = Lists.newArrayList();
            for (String id : spiderInfoIdList) {
                try {
                    SpiderInfo info = spiderInfoService.getById(id).getResult();
                    String taskId = commonSpider.start(info);
                    taskIdList.add(taskId);
                } catch (JMException e) {
                    LOG.error("????????????ID{}?????????{}", id, e);
                }
            }
            return taskIdList;
        });
    }

    /**
     * ??????????????????
     *
     * @param spiderInfoId  ????????????id
     * @param hoursInterval ????????????????????????
     */
    public ResultBundle<String> createQuartzJob(String spiderInfoId, int hoursInterval) {
        SpiderInfo spiderInfo = spiderInfoService.getById(spiderInfoId).getResult();
        Map<String, Object> data = Maps.newHashMap();
        data.put("spiderInfo", spiderInfo);
        data.put("commonsSpiderService", this);
        quartzManager.addJob(spiderInfo.getId(), QUARTZ_JOB_GROUP_NAME,
                String.valueOf(hoursInterval) + "-" + spiderInfo.getId() + QUARTZ_TRIGGER_NAME_SUFFIX, QUARTZ_TRIGGER_GROUP_NAME
                , WebpageSpiderJob.class, data, hoursInterval);
        return bundleBuilder.bundle(spiderInfoId, () -> "OK");
    }

    public ResultBundle<Map<String, Triple<SpiderInfo, JobKey, Trigger>>> listAllQuartzJobs() {
        Map<String, Triple<SpiderInfo, JobKey, Trigger>> result = Maps.newHashMap();
        for (JobKey jobKey : quartzManager.listAll(QUARTZ_JOB_GROUP_NAME)) {
            Pair<JobDetail, Trigger> pair = quartzManager.findInfo(jobKey);
            SpiderInfo spiderInfo = ((SpiderInfo) pair.getLeft().getJobDataMap().get("spiderInfo"));
            result.put(spiderInfo.getId(), Triple.of(spiderInfo, jobKey, pair.getRight()));
        }
        return bundleBuilder.bundle("", () -> result);
    }

    public ResultBundle<String> removeQuartzJob(String spiderInfoId) {
        quartzManager.removeJob(JobKey.jobKey(spiderInfoId, QUARTZ_JOB_GROUP_NAME));
        return bundleBuilder.bundle(spiderInfoId, () -> "OK");
    }

    public ResultBundle<String> checkQuartzJob(String spiderInfoId) {
        try {
            Pair<JobDetail, Trigger> pair = quartzManager.findInfo(JobKey.jobKey(spiderInfoId, QUARTZ_JOB_GROUP_NAME));
            SpiderInfo spiderInfo = spiderInfoService.getById(spiderInfoId).getResult();
            if (pair == null && spiderInfo != null) {
                return bundleBuilder.bundle(spiderInfoId, () -> "true");
            } else {
                return bundleBuilder.bundle(spiderInfoId, () -> "???????????????????????????????????????????????????????????????");
            }
        } catch (Exception e) {
            return bundleBuilder.bundle(spiderInfoId, e::getLocalizedMessage);
        }

    }

    public String exportQuartz() {
        Map<String, Long> result = Maps.newHashMap();
        for (JobKey jobKey : quartzManager.listAll(QUARTZ_JOB_GROUP_NAME)) {
            Pair<JobDetail, Trigger> pair = quartzManager.findInfo(jobKey);
            long hours = ((SimpleTrigger) ((SimpleScheduleBuilder) pair.getRight().getScheduleBuilder()).build()).getRepeatInterval() / DateBuilder.MILLISECONDS_IN_HOUR;
            String name = ((SpiderInfo) pair.getLeft().getJobDataMap().get("spiderInfo")).getId();
            result.put(name, hours);
        }
        return new Gson().toJson(result);
    }

    public void importQuartz(String json) {
        Map<String, Integer> result = new Gson().fromJson(json, new TypeToken<Map<String, Integer>>() {
        }.getType());
        for (Map.Entry<String, Integer> entry : result.entrySet()) {
            createQuartzJob(entry.getKey(), entry.getValue());
        }
    }

}
