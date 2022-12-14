package com.gs.spider.gather.commons;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.gs.spider.dao.CommonWebpageDAO;
import com.gs.spider.dao.CommonWebpagePipeline;
import com.gs.spider.dao.SpiderInfoDAO;
import com.gs.spider.gather.async.AsyncGather;
import com.gs.spider.gather.async.TaskManager;
import com.gs.spider.model.async.State;
import com.gs.spider.model.async.Task;
import com.gs.spider.model.commons.SpiderInfo;
import com.gs.spider.model.commons.Webpage;
import com.gs.spider.utils.NLPExtractor;
import com.gs.spider.utils.StaticValue;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpHost;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilders;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.monitor.SpiderMonitor;
import us.codecraft.webmagic.pipeline.Pipeline;
import us.codecraft.webmagic.pipeline.ResultItemsCollectorPipeline;
import us.codecraft.webmagic.processor.PageProcessor;
import us.codecraft.webmagic.scheduler.QueueScheduler;
import us.codecraft.webmagic.selector.Html;
import us.codecraft.webmagic.selector.PlainText;
import us.codecraft.webmagic.utils.UrlUtils;

import javax.management.JMException;
import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CommonSpider
 *
 * @author Gao Shen
 * @version 16/4/11
 */
public class CommonSpider extends AsyncGather {
    private static final Logger LOG = LogManager.getLogger(CommonSpider.class);
    private static final String LINK_KEY = "LINK_LIST";
    private static final String DYNAMIC_FIELD = "dynamic_fields";
    private static final String SPIDER_INFO = "spiderInfo";
    private static List<String> ignoredUrls;
    //????????????????????????
    private static LinkedList<Pair<String, SimpleDateFormat>> datePattern = Lists.newLinkedList();

    static {
        try {
            ignoredUrls = FileUtils.readLines(new File(CommonSpider.class.getClassLoader().getResource("ignoredUrls.txt").getFile()));
            LOG.info("????????????????????????url??????????????????,????????????:{}", ignoredUrls);
            try {
                String[] datePatternFile = FileUtils.readFileToString(
                        new File(CommonSpider.class.getClassLoader().getResource("datePattern.txt").getFile()),
                        "utf8"
                ).replace("\r", "").split("=====\r?\n");
                String[] dateList = datePatternFile[0].split("\n");
                String[] timeList = datePatternFile[1].split("\n");
                for (String date : dateList) {
                    String[] dateEntry = date.split("##");
                    String dateReg = dateEntry[0];
                    String dateFormat = dateEntry[1];
                    LOG.debug("????????????????????????{},format:{}", dateReg, dateFormat);
                    datePattern.add(Pair.of(dateReg, new SimpleDateFormat(dateFormat)));
                    for (String time : timeList) {
                        String[] timeEntry = time.split("##");
                        String timeReg = timeEntry[0];
                        String timeFormat = timeEntry[1];
                        //??????????????????????????????
                        LOG.debug("????????????????????????{},format:{}", dateReg + " " + timeReg, dateFormat + " " + timeFormat);
                        datePattern.add(Pair.of(dateReg + " " + timeReg, new SimpleDateFormat(dateFormat + " " + timeFormat)));
                        //??????????????????????????????
                        LOG.debug("????????????????????????{},format:{}", dateReg + timeReg, dateFormat + timeFormat);
                        datePattern.add(Pair.of(dateReg + timeReg, new SimpleDateFormat(dateFormat + timeFormat)));
                    }
                }
                datePattern.sort((o1, o2) -> o2.getLeft().length() - o1.getLeft().length());
                LOG.info("???????????????????????????");
            } catch (IOException e) {
                LOG.error("??????????????????????????????{}", e.getLocalizedMessage());
            }
        } catch (IOException e) {
            e.printStackTrace();
            LOG.error("????????????????????????url??????????????????", e);
        }
    }

    //??????????????????,????????????????????????
    private SpiderMonitor spiderMonitor = SpiderMonitor.instance();
    private Map<String, MySpider> spiderMap = new HashMap<>();
    private NLPExtractor keywordsExtractor;
    private NLPExtractor summaryExtractor;
    private NLPExtractor namedEntitiesExtractor;
    private StaticValue staticValue;
    @SuppressWarnings("unchecked")
    private final PageConsumer spiderInfoPageConsumer = (page, info, task) -> {
        try {
            long start = System.currentTimeMillis();
//        if (page.getHtml().getDocument().getElementsByTag("a").size() <= 0) {
//            page.setSkip(true);
//            return;
//        }
            //???????????????startUrls???????????????
            final boolean startPage = info.getStartURL().contains(page.getUrl().get());
            List<String> attachmentList = Lists.newLinkedList();
            //???????????????????????????????????????,?????????????????????????????????
            if (!info.isGatherFirstPage() || (info.isGatherFirstPage() && startPage)) {
                List<String> links = null;
                if (StringUtils.isNotBlank(info.getUrlReg())) {//url??????????????????
                    links = page.getHtml().links().regex(info.getUrlReg()).all().stream()
                            .map(s -> {
                                int indexOfSharp = s.indexOf("#");
                                return s.substring(0, indexOfSharp == -1 ? s.length() : indexOfSharp);
                            }).collect(Collectors.toList());
                } else {//url???????????????????????????????????????????????????,???????????????????????????????????????
                    links = page.getHtml().links()
                            .regex("https?://" + info.getDomain().replace(".", "\\.") + "/.*")
                            .all().stream().map(s -> {
                                int indexOfSharp = s.indexOf("#");
                                return s.substring(0, indexOfSharp == -1 ? s.length() : indexOfSharp);
                            })
                            .filter(s -> {
                                for (String ignoredPostfix : ignoredUrls) {
                                    if (s.toLowerCase().endsWith(ignoredPostfix)) {
                                        return false;
                                    }
                                }
                                return true;
                            }).collect(Collectors.toList());
                }
                //??????????????????iframe??????????????????
                for (Element iframe : page.getHtml().getDocument().getElementsByTag("iframe")) {
                    final String src = iframe.attr("src");
                    //iframe???????????????????????????url??????
                    if (StringUtils.isNotBlank(info.getUrlReg()) && src.matches(info.getUrlReg())) {
                        links.add(src);
                    }
                    //??????url??????,?????????????????????
                    else if (StringUtils.isBlank(info.getUrlReg()) && UrlUtils.getDomain(src).equals(info.getDomain())) {
                        links.add(src);
                    }
                }
                if (links != null && links.size() > 0) {
                    page.addTargetRequests(links);
                    //??????debug????????????????????????????????????????????????
                    if (staticValue.isCommonsSpiderDebug()) {
                        List<String> urls;
                        if ((urls = ((List<String>) task.getExtraInfoByKey(LINK_KEY))) != null) {
                            urls.addAll(links);
                        } else {
                            task.addExtraInfo(LINK_KEY, links);
                        }
                    }
                }
            }
            //??????startUrl??????
            if (startPage) {
                page.setSkip(true);
            }
            page.putField("url", page.getUrl().get());
            page.putField("domain", info.getDomain());
            page.putField("spiderInfoId", info.getId());
            page.putField("gatherTime", new Date());
            page.putField("spiderInfo", info);
            page.putField("spiderUUID", task.getTaskId());
            if (info.isSaveCapture()) {
                page.putField("rawHTML", page.getHtml().get());
            }
            //??????????????????
            if (info.getStaticFields() != null && info.getStaticFields().size() > 0) {
                Map<String, String> staticFieldList = Maps.newHashMap();
                for (SpiderInfo.StaticField staticField : info.getStaticFields()) {
                    staticFieldList.put(staticField.getName(), staticField.getValue());
                }
                page.putField("staticField", staticFieldList);
            }
            ///////////////////////////////////////////////////////
            String content;
            if (!StringUtils.isBlank(info.getContentXPath())) {//??????????????????XPath??????????????????XPath
                StringBuilder buffer = new StringBuilder();
                page.getHtml().xpath(info.getContentXPath()).all().forEach(buffer::append);
                content = buffer.toString();
            } else if (!StringUtils.isBlank(info.getContentReg())) {//????????????XPath
                StringBuilder buffer = new StringBuilder();
                page.getHtml().regex(info.getContentReg()).all().forEach(buffer::append);
                content = buffer.toString();
            } else {//??????????????????????????????????????????????????????
                Document clone = page.getHtml().getDocument().clone();
                clone.getElementsByTag("p").append("***");
                clone.getElementsByTag("br").append("***");
                clone.getElementsByTag("script").remove();
                //?????????????????????
                clone.getElementsByAttributeValueContaining("style", "display:none").remove();
                content = new Html(clone).smartContent().get();
            }
            content = content.replaceAll("<script([\\s\\S]*?)</script>", "");
            content = content.replaceAll("<style([\\s\\S]*?)</style>", "");
            content = content.replace("</p>", "***");
            content = content.replace("<BR>", "***");
            content = content.replaceAll("<([\\s\\S]*?)>", "");

            content = content.replace("***", "<br/>");
            content = content.replace("\n", "<br/>");
            content = content.replaceAll("(\\<br/\\>\\s*){2,}", "<br/> ");
            content = content.replaceAll("(&nbsp;\\s*)+", " ");
            page.putField("content", content);
            if (info.isNeedContent() && StringUtils.isBlank(content)) {//if the content is blank ,skip it!
                page.setSkip(true);
                return;
            }
            //????????????
            String title = null;
            if (!StringUtils.isBlank(info.getTitleXPath())) {//??????????????????
                title = page.getHtml().xpath(info.getTitleXPath()).get();
            } else if (!StringUtils.isBlank(info.getTitleReg())) {
                title = page.getHtml().regex(info.getTitleReg()).get();
            } else {//?????????????????????title
                title = page.getHtml().getDocument().title();
            }
            page.putField("title", title);
            if (info.isNeedTitle() && StringUtils.isBlank(title)) {//if the title is blank ,skip it!
                page.setSkip(true);
                return;
            }

            //??????????????????
            Map<String, Object> dynamicFields = Maps.newHashMap();
            for (SpiderInfo.FieldConfig conf : info.getDynamicFields()) {
                String fieldName = conf.getName();
                String fieldData = null;
                if (!StringUtils.isBlank(conf.getXpath())) {//??????
                    fieldData = page.getHtml().xpath(conf.getXpath()).get();
                } else if (!StringUtils.isBlank(conf.getRegex())) {
                    fieldData = page.getHtml().regex(conf.getRegex()).get();
                }
                dynamicFields.put(fieldName, fieldData);
                if (conf.isNeed() && StringUtils.isBlank(fieldData)) {//if the field data is blank ,skip it!
                    page.setSkip(true);
                    return;
                }
            }
            page.putField(DYNAMIC_FIELD, dynamicFields);

            //????????????
            String category = null;
            if (!StringUtils.isBlank(info.getCategoryXPath())) {//??????????????????
                category = page.getHtml().xpath(info.getCategoryXPath()).get();
            } else if (!StringUtils.isBlank(info.getCategoryReg())) {
                category = page.getHtml().regex(info.getCategoryReg()).get();
            }
            if (StringUtils.isNotBlank(category)) {
                page.putField("category", category);
            } else {
                page.putField("category", info.getDefaultCategory());
            }

            //??????????????????
            String publishTime = null;
            if (!StringUtils.isBlank(info.getPublishTimeXPath())) {//????????????????????????
                publishTime = page.getHtml().xpath(info.getPublishTimeXPath()).get();
            } else if (!StringUtils.isBlank(info.getPublishTimeReg())) {
                publishTime = page.getHtml().regex(info.getPublishTimeReg()).get();
            }
            Date publishDate = null;
            SimpleDateFormat simpleDateFormat = null;
            //??????SimpleDateFormat??????????????????,?????????????????????????????????,???????????????????????????
            if (StringUtils.isNotBlank(info.getPublishTimeFormat())) {
                //?????????????????????????????????????????????
                if (StringUtils.isNotBlank(info.getLang())) {
                    simpleDateFormat = new SimpleDateFormat(info.getPublishTimeFormat(), new Locale(info.getLang(), info.getCountry()));
                } else {
                    simpleDateFormat = new SimpleDateFormat(info.getPublishTimeFormat());
                }
            } else if (StringUtils.isBlank(publishTime) && info.isAutoDetectPublishDate()) {
                //?????????????????????????????????????????????????????????,??????????????????????????????,?????????????????????????????????
                for (Pair<String, SimpleDateFormat> formatEntry : datePattern) {
                    publishTime = page.getHtml().regex(formatEntry.getKey(), 0).get();
                    //???????????????????????????????????????
                    if (StringUtils.isNotBlank(publishTime)) {
                        simpleDateFormat = formatEntry.getValue();
                        break;
                    }
                }
            }
            //?????????????????????date??????
            if (simpleDateFormat != null && StringUtils.isNotBlank(publishTime)) {
                try {
                    publishDate = simpleDateFormat.parse(publishTime);
                    //??????????????????????????????,????????????????????????
                    if (!simpleDateFormat.toPattern().contains("yyyy")) {
                        Calendar calendar = Calendar.getInstance();
                        calendar.setTime(publishDate);
                        calendar.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR));
                        publishDate = calendar.getTime();
                    }
                    page.putField("publishTime", publishDate);
                } catch (ParseException e) {
                    LOG.debug("??????????????????????????????,source:" + publishTime + ",format:" + simpleDateFormat.toPattern());
                    task.setDescription("??????????????????????????????,url:%s source:%s ,format:%s", page.getUrl().toString(), publishTime, simpleDateFormat.toPattern());
                    if (info.isNeedPublishTime()) {//if the publishTime is blank ,skip it!
                        page.setSkip(true);
                        return;
                    }
                }
            } else if (info.isNeedPublishTime()) {//if the publishTime is blank ,skip it!
                page.setSkip(true);
                return;
            }
            ///////////////////////////////////////////////////////
            if (info.isDoNLP()) {//???????????????????????????????????????????????????
                //??????nlp???????????????????????????
                String contentWithoutHtml = content.replaceAll("<br/>", "");
                try {
                    //???????????????,10??????
                    page.putField("keywords", keywordsExtractor.extractKeywords(contentWithoutHtml));
                    //????????????,5??????
                    page.putField("summary", summaryExtractor.extractSummary(contentWithoutHtml));
                    //??????????????????
                    page.putField("namedEntity", namedEntitiesExtractor.extractNamedEntity(contentWithoutHtml));
                } catch (Exception e) {
                    e.printStackTrace();
                    LOG.error("???????????????NLP????????????,{}", e.getLocalizedMessage());
                    task.setDescription("???????????????NLP????????????,%s", e.getLocalizedMessage());
                }
            }
            //?????????????????????
            page.putField("processTime", System.currentTimeMillis() - start);
        } catch (Exception e) {
            task.setDescription("?????????????????????%s", e.toString());
        }
    };
    private CasperjsDownloader casperjsDownloader;
    private List<Pipeline> pipelineList;
    private CommonWebpagePipeline commonWebpagePipeline;
    private ContentLengthLimitHttpClientDownloader contentLengthLimitHttpClientDownloader;
    private CommonWebpageDAO commonWebpageDAO;
    private SpiderInfoDAO spiderInfoDAO;

    @Autowired
    public CommonSpider(TaskManager taskManager, StaticValue staticValue) throws InterruptedException, BindException {
        this.taskManager = taskManager;
        this.staticValue = staticValue;
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                deleteAll();
                LOG.debug("???????????????????????????????????????????????????");
            }
        }, staticValue.getTaskDeleteDelay() * 3600000, staticValue.getTaskDeletePeriod() * 3600000);
        LOG.debug("?????????????????????????????????????????????????????????,??????:{}??????,???{}??????????????????", staticValue.getTaskDeleteDelay(), staticValue.getTaskDeletePeriod());
    }

    /**
     * ????????????
     *
     * @param uuid
     */
    public void stop(String uuid) {
        Preconditions.checkArgument(spiderMap.containsKey(uuid), "?????????UUID???%s?????????,???????????????", uuid);
        spiderMap.get(uuid).close();
        spiderMap.get(uuid).stop();
        taskManager.getTaskById(uuid).setState(State.STOP);
    }

    /**
     * ????????????
     *
     * @param uuid
     */
    public void delete(String uuid) {
        Preconditions.checkArgument(spiderMap.containsKey(uuid) || taskManager.getTaskById(uuid) != null, "?????????UUID???%s?????????,???????????????", uuid);
        Preconditions.checkArgument(taskManager.getTaskById(uuid).getState() == State.STOP, "??????" + uuid + "????????????,??????????????????");
        deleteTaskById(uuid);
        spiderMap.remove(uuid);
    }

    /**
     * ??????????????????
     */
    public void deleteAll() {
        List<String> spiderUUID2BeRemoved = spiderMap.entrySet().stream().filter(
                spiderEntry -> spiderEntry.getValue().getStatus() == Spider.Status.Stopped
        ).map(Map.Entry::getKey).collect(Collectors.toList());
        for (String uuid : spiderUUID2BeRemoved) {
            try {
                deleteTaskById(uuid);
                spiderMap.remove(uuid);
            } catch (Exception e) {
                LOG.error("????????????ID:{}??????,{}", uuid, e.getLocalizedMessage());
            }
        }
        taskManager.deleteTasksByState(State.STOP);
    }

    /**
     * ??????ID??????????????????
     *
     * @param uuid
     * @return
     */
    public MySpider getSpiderById(String uuid) {
        Preconditions.checkArgument(spiderMap.containsKey(uuid), "?????????UUID???%s?????????,???????????????", uuid);
        return spiderMap.get(uuid);
    }

    /**
     * ????????????
     *
     * @param info ??????????????????
     * @return
     * @throws JMException
     */
    public String start(SpiderInfo info) throws JMException {
        boolean running = taskManager.findTaskBy(task -> {
            Object spiderinfoObj = task.getExtraInfoByKey(SPIDER_INFO);
            if (spiderinfoObj != null && spiderinfoObj instanceof SpiderInfo) {
                SpiderInfo spiderInfo = (SpiderInfo) spiderinfoObj;
                return task.getState() == State.RUNNING && spiderInfo.getId().equals(info.getId());
            }
            return false;
        });
        Preconditions.checkArgument(!running, "???????????????????????????,????????????%s,??????????????????", info.getId());
        final String uuid = UUID.randomUUID().toString();
        Task task = taskManager.initTask(uuid, info.getDomain(), info.getCallbackURL(), "spiderInfoId=" + info.getId() + "&spiderUUID=" + uuid);
        task.addExtraInfo(SPIDER_INFO, info);
        QueueScheduler scheduler = new QueueScheduler() {
            @Override
            public void pushWhenNoDuplicate(Request request, us.codecraft.webmagic.Task task) {
                int left = getLeftRequestsCount(task);
                if (left <= staticValue.getLimitOfCommonWebpageDownloadQueue()) {
                    super.pushWhenNoDuplicate(request, task);
                }
            }
        };
        if (staticValue.isNeedEs()) {
            scheduler.setDuplicateRemover(commonWebpagePipeline);
        }
        MySpider spider = (MySpider) makeSpider(info, task)
                .setScheduler(scheduler);
        //???????????????????????????
        if (pipelineList != null && pipelineList.size() > 0) {
            pipelineList.forEach(spider::addPipeline);
        }
        info.getStartURL().forEach(s -> scheduler.pushWhenNoDuplicate(new Request(s), spider));
        //??????????????????,????????????????????????
//        spiderMonitor.register(spider);
        spiderMap.put(uuid, spider);
        spider.start();
        taskManager.getTaskById(uuid).setState(State.RUNNING);
        return uuid;
    }

    /**
     * ??????????????????
     *
     * @param info
     * @return
     */
    public List<Webpage> testSpiderInfo(SpiderInfo info) throws JMException {
        final ResultItemsCollectorPipeline resultItemsCollectorPipeline = new ResultItemsCollectorPipeline();
        final String uuid = UUID.randomUUID().toString();
        Task task = taskManager.initTask(uuid, info.getDomain(), info.getCallbackURL(), "spiderInfoId=" + info.getId() + "&spiderUUID=" + uuid);
        task.addExtraInfo("spiderInfo", info);
        QueueScheduler queueScheduler = new QueueScheduler();
        MySpider spider = (MySpider) makeSpider(info, task)
                .addPipeline(resultItemsCollectorPipeline)
                .setScheduler(queueScheduler);
        if (info.isAjaxSite() && StringUtils.isNotBlank(staticValue.getAjaxDownloader())) {
            spider.setDownloader(casperjsDownloader);
        } else {
            spider.setDownloader(contentLengthLimitHttpClientDownloader);
        }
        spider.startUrls(info.getStartURL());
        //??????????????????,????????????????????????
//        spiderMonitor.register(spider);
        spiderMap.put(uuid, spider);
        taskManager.getTaskById(uuid).setState(State.RUNNING);
        spider.run();
        List<Webpage> webpageList = Lists.newLinkedList();
        resultItemsCollectorPipeline.getCollected().forEach(resultItems -> webpageList.add(CommonWebpagePipeline.convertResultItems2Webpage(resultItems)));
        return webpageList;
    }

    /**
     * ????????????????????????????????????
     *
     * @return
     */
    public Map<String, Map<Object, Object>> listAllSpiders(boolean containsExtraInfo) {
        Map<String, Map<Object, Object>> result = new HashMap<>();
        spiderMap.entrySet().forEach(spiderEntry -> {
            MySpider spider = spiderEntry.getValue();
            result.put(spiderEntry.getKey(), makeSpiderRuntimeInfo(spider, containsExtraInfo));
        });
        return result;
    }

    /**
     * ???????????????????????????
     *
     * @param uuid ??????uuid ??????id
     * @return
     */
    public Map<Object, Object> getSpiderRuntimeInfo(String uuid, boolean containsExtraInfo) {
        return makeSpiderRuntimeInfo(getSpiderById(uuid), containsExtraInfo);
    }

    /**
     * ???????????????????????????
     *
     * @param spider
     * @return
     */
    private Map<Object, Object> makeSpiderRuntimeInfo(MySpider spider, boolean containsExtraInfo) {
        Map<Object, Object> infoMap = new HashMap<>();
        infoMap.put("PageCount", spider.getPageCount());
        infoMap.put("StartTime", spider.getStartTime());
        infoMap.put("ThreadAlive", spider.getThreadAlive());
        infoMap.put("Status", spider.getStatus());
        infoMap.put("SpiderInfo", spider.getSpiderInfo());
        if (containsExtraInfo) {
            infoMap.put("Links", getTaskById(spider.getUUID(), true).getExtraInfoByKey(LINK_KEY));
        }
        return infoMap;
    }

    /**
     * ????????????url?????????
     *
     * @return
     */
    public List<String> getIgnoredUrls() {
        return ignoredUrls;
    }

    /**
     * ????????????url?????????
     *
     * @param postfix
     */
    public void addIgnoredUrl(String postfix) {
        Preconditions.checkArgument(!ignoredUrls.contains(postfix), "???????????????url????????????????????????");
        ignoredUrls.add(postfix);
    }

    /**
     * ????????????domain????????????
     *
     * @param domain ????????????
     * @return ??????????????????????????????
     */
    public String deleteByDomain(String domain) {
        final String uuid = UUID.randomUUID().toString();
        Task task = taskManager.initTask(uuid, "DELETE BY DOMAIN:" + domain, Lists.newArrayList(), null);
        Thread thread = new Thread(() -> {
            task.setState(State.RUNNING);
            try {
                commonWebpageDAO.deleteByDomain(domain, task);
            } catch (Exception e) {
                task.setDescription("???????????????????????????{}", e.toString() + e.getLocalizedMessage());
            } finally {
                task.setState(State.STOP);
            }
        }, "delete-webpage-thread-" + domain);
        thread.start();
        return uuid;
    }

    /**
     * ??????spiderinfoID????????????
     *
     * @param spiderInfoIdUpdateBy ???????????????????????????
     * @param callbackUrls         ????????????
     * @param spiderInfo           ????????????????????????
     * @return ??????????????????????????????
     */
    public String updateBySpiderinfoID(String spiderInfoIdUpdateBy, SpiderInfo spiderInfo, List<String> callbackUrls) {
        Preconditions.checkArgument(StringUtils.isNotBlank(spiderInfo.getId()), "?????????????????????ID??????");
        SpiderInfo inDatabase = spiderInfoDAO.getById(spiderInfo.getId());
        Preconditions.checkArgument(inDatabase != null, "????????????????????????????????????");
        final String uuid = UUID.randomUUID().toString();
        Task task = taskManager.initTask(uuid, "Update By SpiderinfoID:" + spiderInfoIdUpdateBy, callbackUrls, "spiderInfoIdUpdateBy" + spiderInfoIdUpdateBy);
        Thread thread = new Thread(() -> {
            task.setState(State.RUNNING);
            try {
                Pair<String, List<Webpage>> pair = commonWebpageDAO.startScroll(QueryBuilders.matchQuery("spiderInfoId", spiderInfoIdUpdateBy).operator(Operator.AND), 50);
                int scrollPage = 0;//?????????????????????
                List<Webpage> webpageList = pair.getRight();
                while (webpageList.size() > 0) {
                    List<Webpage> newWebpageList = Lists.newArrayList();
                    for (Webpage webpage : webpageList) {
                        try {
                            //??????page??????
                            Page page = new Page();
                            Request request = new Request(webpage.getUrl());
                            page.setRequest(request);
                            page.setRawText(webpage.getRawHTML());
                            page.setUrl(new PlainText(webpage.getUrl()));
                            //???????????????????????????
                            spiderInfoPageConsumer.accept(page, spiderInfo, task);
                            //??????????????????
                            newWebpageList.add(CommonWebpagePipeline.convertResultItems2Webpage(page.getResultItems()));
                        } catch (Exception e) {
                            LOG.error("???????????????????????????,webpageID:{},error:{}", webpage.getId(), e.getLocalizedMessage());
                        } finally {
                            task.increaseCount();
                        }
                    }
                    //??????????????????
                    boolean hasFail = commonWebpageDAO.update(newWebpageList);
                    task.setDescription("????????????%s?????????,??????:%s", ++scrollPage, hasFail);
                    webpageList = commonWebpageDAO.scrollAllWebpage(pair.getLeft());
                }
            } catch (Exception e) {
                task.setDescription("??????spiderinfoID???????????????????????????%s", e.toString() + e.getLocalizedMessage());
            } finally {
                task.setState(State.STOP);
                taskManager.stopTask(task);
            }
        }, "webpage-update-thread-" + spiderInfoIdUpdateBy);
        thread.start();
        return uuid;
    }

    /**
     * ????????????
     *
     * @param info ????????????
     * @param task ????????????
     * @return
     */
    private MySpider makeSpider(SpiderInfo info, Task task) {
        MySpider spider = ((MySpider) new MySpider(new MyPageProcessor(info, task), info)
                .thread(info.getThread())
                .setUUID(task.getTaskId()));
        if (info.isAjaxSite() && StringUtils.isNotBlank(staticValue.getAjaxDownloader())) {
            spider.setDownloader(casperjsDownloader);
        } else {
            spider.setDownloader(contentLengthLimitHttpClientDownloader);
        }
        return spider;
    }

    public NLPExtractor getKeywordsExtractor() {
        return keywordsExtractor;
    }

    public void setKeywordsExtractor(NLPExtractor keywordsExtractor) {
        this.keywordsExtractor = keywordsExtractor;
    }

    public CommonWebpagePipeline getCommonWebpagePipeline() {
        return commonWebpagePipeline;
    }

    public CommonSpider setCommonWebpagePipeline(CommonWebpagePipeline commonWebpagePipeline) {
        this.commonWebpagePipeline = commonWebpagePipeline;
        return this;
    }

    public NLPExtractor getSummaryExtractor() {
        return summaryExtractor;
    }

    public void setSummaryExtractor(NLPExtractor summaryExtractor) {
        this.summaryExtractor = summaryExtractor;
    }

    public NLPExtractor getNamedEntitiesExtractor() {
        return namedEntitiesExtractor;
    }

    public CommonSpider setNamedEntitiesExtractor(NLPExtractor namedEntitiesExtractor) {
        this.namedEntitiesExtractor = namedEntitiesExtractor;
        return this;
    }

    public ContentLengthLimitHttpClientDownloader getContentLengthLimitHttpClientDownloader() {
        return contentLengthLimitHttpClientDownloader;
    }

    public CommonSpider setContentLengthLimitHttpClientDownloader(ContentLengthLimitHttpClientDownloader contentLengthLimitHttpClientDownloader) {
        this.contentLengthLimitHttpClientDownloader = contentLengthLimitHttpClientDownloader;
        return this;
    }

    public CommonWebpageDAO getCommonWebpageDAO() {
        return commonWebpageDAO;
    }

    public CommonSpider setCommonWebpageDAO(CommonWebpageDAO commonWebpageDAO) {
        this.commonWebpageDAO = commonWebpageDAO;
        return this;
    }

    public SpiderInfoDAO getSpiderInfoDAO() {
        return spiderInfoDAO;
    }

    public CommonSpider setSpiderInfoDAO(SpiderInfoDAO spiderInfoDAO) {
        this.spiderInfoDAO = spiderInfoDAO;
        return this;
    }

    public List<Pipeline> getPipelineList() {
        return pipelineList;
    }

    public CommonSpider setPipelineList(List<Pipeline> pipelineList) {
        this.pipelineList = pipelineList;
        return this;
    }

    public CasperjsDownloader getCasperjsDownloader() {
        return casperjsDownloader;
    }

    public CommonSpider setCasperjsDownloader(CasperjsDownloader casperjsDownloader) {
        this.casperjsDownloader = casperjsDownloader;
        return this;
    }

    /**
     * ????????????webmagic????????????????????????????????????
     */
    private class MySpider extends Spider {
        private final SpiderInfo SPIDER_INFO;
        private Logger LOG = LogManager.getLogger(MySpider.class);

        MySpider(PageProcessor pageProcessor, SpiderInfo spiderInfo) {
            super(pageProcessor);
            this.SPIDER_INFO = spiderInfo;
        }

        @Override
        protected void onSuccess(Request request) {
            super.onSuccess(request);
            Task task = taskManager.getTaskById(this.getUUID());
            boolean reachMax = false, exceedRatio = false;
            if (
                    (
                            //???????????????????????????????????????,??????
                            (reachMax = (SPIDER_INFO.getMaxPageGather() > 0 && task.getCount() >= SPIDER_INFO.getMaxPageGather()))
                                    ||
                                    //??????????????????????????????????????????ratio????????????,??????????????????????????????,???????????????
                                    (exceedRatio = (this.getPageCount() > SPIDER_INFO.getMaxPageGather() * staticValue.getCommonsWebpageCrawlRatio() && SPIDER_INFO.getMaxPageGather() > 0))
                    )
                            && this.getStatus() == Status.Running) {
                LOG.info("??????ID{}?????????{}?????????,????????????{}???,??????????????????{},reachMax={},exceedRatio={},??????.", this.getUUID(), this.getPageCount(), task.getCount(), SPIDER_INFO.getMaxPageGather(), reachMax, exceedRatio);
                task.setDescription("??????ID%s?????????%s?????????,????????????%s???,????????????????????????%s,reachMax=%s,exceedRatio=%s,??????.", this.getUUID(), this.getPageCount(), task.getCount(), SPIDER_INFO.getMaxPageGather(), reachMax, exceedRatio);
                this.stop();
            }
        }

        @Override
        protected void onError(Request request) {
            super.onError(request);
            Task task = taskManager.getTaskById(this.getUUID());
            task.setDescription("????????????%s???????????????,%s", request.getUrl(), request.getExtras());
        }

        @Override
        public void close() {
            super.close();
            Task task = taskManager.getTaskById(this.getUUID());
            if (task != null) {
                //????????????????????????
                commonWebpagePipeline.deleteUrls(task.getTaskId());
                taskManager.stopTask(task);
            }
        }

        SpiderInfo getSpiderInfo() {
            return SPIDER_INFO;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;

            if (o == null || getClass() != o.getClass()) return false;

            MySpider mySpider = (MySpider) o;

            return new EqualsBuilder()
                    .append(this.getUUID(), mySpider.getUUID())
                    .isEquals();
        }

        @Override
        public int hashCode() {
            return new HashCodeBuilder(17, 37)
                    .append(this.getUUID())
                    .toHashCode();
        }
    }

    private class MyPageProcessor implements PageProcessor {

        private Site site;
        private SpiderInfo info;
        private Task task;

        public MyPageProcessor(SpiderInfo info, Task task) {
            this.site = Site.me().setDomain(info.getDomain()).setTimeOut(info.getTimeout())
                    .setRetryTimes(info.getRetry()).setSleepTime(info.getSleep())
                    .setCharset(StringUtils.isBlank(info.getCharset()) ? null : info.getCharset())
                    .setUserAgent(info.getUserAgent());
            //??????????????????IP?????????
            if (StringUtils.isNotBlank(info.getProxyHost()) && info.getProxyPort() > 0) {
                this.site.setHttpProxy(new HttpHost(info.getProxyHost(), info.getProxyPort()));
                //?????????????????????
                if (StringUtils.isNotBlank(info.getProxyUsername()) && StringUtils.isNotBlank(info.getProxyPassword())) {
                    this.site.setUsernamePasswordCredentials(new UsernamePasswordCredentials(info.getProxyUsername(), info.getProxyPassword()));
                }
            }
            this.info = info;
            this.task = task;
        }

        @Override
        public void process(Page page) {
            spiderInfoPageConsumer.accept(page, info, task);
            if (!page.getResultItems().isSkip()) {//??????????????????????????????
                task.increaseCount();
            }
        }

        @Override
        public Site getSite() {
            return site;
        }
    }
}
