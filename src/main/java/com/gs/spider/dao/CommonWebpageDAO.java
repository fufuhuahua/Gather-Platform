package com.gs.spider.dao;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.*;
import com.gs.spider.model.async.Task;
import com.gs.spider.model.commons.Webpage;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.sort.SortOrder;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.index.query.QueryBuilders.moreLikeThisQuery;

/**
 * CommonWebpageDAO
 *
 * @author Gao Shen
 * @version 16/4/18
 */
@Component
public class CommonWebpageDAO extends IDAO<Webpage> {
    private final static String INDEX_NAME = "commons", TYPE_NAME = "webpage";
    private static final int SCROLL_TIMEOUT = 1;
    private static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Date.class, (JsonDeserializer<Date>) (json, typeOfT, context) -> new Date(json.getAsJsonPrimitive().getAsLong()))
            .registerTypeAdapter(Date.class, (JsonSerializer<Date>) (src, typeOfSrc, context) -> new JsonPrimitive(src.getTime()))
            .setDateFormat(DateFormat.LONG).create();
    private Logger LOG = LogManager.getLogger(CommonWebpageDAO.class);

    @Autowired
    public CommonWebpageDAO(ESClient esClient) {
        super(esClient, INDEX_NAME, TYPE_NAME);
    }

    public CommonWebpageDAO() {
    }

    @Override
    public String index(Webpage webpage) {
        IndexResponse indexResponse = null;
        try {
            indexResponse = client.prepareIndex(INDEX_NAME, TYPE_NAME)
                    .setSource(gson.toJson(webpage))
                    .get();
            return indexResponse.getId();
        } catch (Exception e) {
            LOG.error("?????? Webpage ??????," + e.getLocalizedMessage());
        }
        return null;
    }

    @Override
    protected boolean check() {
        return esClient.checkCommonsIndex() && esClient.checkWebpageType();
    }

    /**
     * ??????uuid????????????
     *
     * @param uuid ??????uuid
     * @param size ????????????
     * @param page ??????
     * @return
     */
    public List<Webpage> getWebpageBySpiderUUID(String uuid, int size, int page) {
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setQuery(QueryBuilders.matchQuery("spiderUUID", uuid))
                .setSize(size).setFrom(size * (page - 1));
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        return warpHits2List(response.getHits());
    }

    /**
     * ?????? ??????-?????? ???
     *
     * @param queryBuilder ??????
     * @param outputStream ???????????????
     */
    private void exportTitleContentPairBy(QueryBuilder queryBuilder, OutputStream outputStream) {
        exportData(queryBuilder,
                searchResponse -> {
                    List<List<String>> resultList = Lists.newLinkedList();
                    List<Webpage> webpageList = warpHits2List(searchResponse.getHits());
                    webpageList.forEach(webpage -> resultList.add(Lists.newArrayList(webpage.getTitle())));
                    return resultList;
                },
                searchResponse -> {
                    List<String> resultList = Lists.newLinkedList();
                    List<Webpage> webpageList = warpHits2List(searchResponse.getHits());
                    webpageList.forEach(webpage -> resultList.add(webpage.getContent()));
                    return resultList;
                }, outputStream);
    }

    /**
     * ????????????id?????? ??????-?????? ???
     *
     * @param uuid         ??????id
     * @param outputStream ???????????????
     */
    public void exportTitleContentPairBySpiderUUID(String uuid, OutputStream outputStream) {
        exportTitleContentPairBy(QueryBuilders.matchQuery("spiderUUID", uuid).operator(Operator.AND), outputStream);
    }

    /**
     * ?????? webpage???JSON??????
     *
     * @param queryBuilder ??????
     * @param includeRaw   ????????????????????????
     * @param outputStream ???????????????
     */
    private void exportWebpageJSONBy(QueryBuilder queryBuilder, Boolean includeRaw, OutputStream outputStream) {
        exportData(queryBuilder,
                searchResponse -> Lists.newLinkedList(),
                searchResponse -> {
                    List<String> resultList = Lists.newLinkedList();
                    List<Webpage> webpageList = warpHits2List(searchResponse.getHits());
                    webpageList.forEach(webpage -> resultList.add(gson.toJson(includeRaw ? webpage : webpage.setRawHTML(null))));
                    return resultList;
                }, outputStream);
    }

    /**
     * ????????????id?????? webpage???JSON??????
     *
     * @param uuid         ??????id
     * @param includeRaw   ????????????????????????
     * @param outputStream ???????????????
     */
    public void exportWebpageJSONBySpiderUUID(String uuid, Boolean includeRaw, OutputStream outputStream) {
        exportWebpageJSONBy(QueryBuilders.matchQuery("spiderUUID", uuid).operator(Operator.AND), includeRaw, outputStream);
    }

    /**
     * ??????domain?????? webpage???JSON??????
     *
     * @param domain       ??????
     * @param includeRaw   ????????????????????????
     * @param outputStream ???????????????
     */
    public void exportWebpageJSONByDomain(String domain, Boolean includeRaw, OutputStream outputStream) {
        exportWebpageJSONBy(QueryBuilders.matchQuery("domain", domain).operator(Operator.AND), includeRaw, outputStream);
    }

    /**
     * ??????ES??????id????????????
     *
     * @param id ??????id
     * @return
     */
    public Webpage getWebpageById(String id) {
        GetResponse response = client.prepareGet(INDEX_NAME, TYPE_NAME, id).get();
        Preconditions.checkArgument(response.isExists(), "????????????ID???%s?????????,???????????????", id);
        return warpHits2Info(response.getSourceAsString(), id);
    }

    /**
     * ??????id????????????
     *
     * @param id ??????id
     * @return ????????????
     */
    public boolean deleteById(String id) {
        DeleteResponse response = client.prepareDelete(INDEX_NAME, TYPE_NAME, id).get();
        return response.getResult() == DeleteResponse.Result.DELETED;
    }

    /**
     * ??????domain????????????,????????????????????????
     *
     * @param domain ????????????
     * @param size   ????????????
     * @param page   ??????
     * @return
     */
    public List<Webpage> getWebpageByDomain(String domain, int size, int page) {
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setQuery(QueryBuilders.matchQuery("domain", domain))
                .addSort("gatherTime", SortOrder.DESC)
                .setSize(size).setFrom(size * (page - 1));
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        return warpHits2List(response.getHits());
    }

    /**
     * ??????domain??????????????????
     *
     * @param domain ??????????????????
     * @param size   ????????????
     * @param page   ??????
     * @return
     */
    public List<Webpage> getWebpageByDomains(Collection<String> domain, int size, int page) {
        if (domain.size() == 0) return Lists.newArrayList();
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        domain.forEach(s -> boolQueryBuilder.should(QueryBuilders.matchQuery("domain", s)));
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setQuery(boolQueryBuilder)
                .addSort("gatherTime", SortOrder.DESC)
                .setSize(size).setFrom(size * (page - 1));
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        return warpHits2List(response.getHits());
    }

    private Webpage warpHits2Info(SearchHit hit) {
        Webpage webpage = gson.fromJson(hit.getSourceAsString(), Webpage.class);
        webpage.setId(hit.getId());
        return webpage;
    }

    private Webpage warpHits2Info(String jsonSource, String id) {
        Webpage webpage = gson.fromJson(jsonSource, Webpage.class);
        webpage.setId(id);
        return webpage;
    }

    private List<Webpage> warpHits2List(SearchHits hits) {
        List<Webpage> webpageList = Lists.newLinkedList();
        hits.forEach(searchHitFields -> {
            webpageList.add(warpHits2Info(searchHitFields));
        });
        return webpageList;
    }

    /**
     * ??????es????????????
     *
     * @param query ?????????
     * @param size  ????????????
     * @param page  ??????
     * @return
     */
    public List<Webpage> searchByQuery(String query, int size, int page) {
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setQuery(QueryBuilders.queryStringQuery(query).analyzer("query_ansj").defaultField("content"))
                .setSize(size).setFrom(size * (page - 1));
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        return warpHits2List(response.getHits());
    }

    /**
     * ????????????????????????,???????????????????????????
     *
     * @param size ????????????
     * @param page ??????
     * @return
     */
    public List<Webpage> listAll(int size, int page) {
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setQuery(QueryBuilders.matchAllQuery())
                .addSort("gatherTime", SortOrder.DESC)
                .setSize(size).setFrom(size * (page - 1));
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        return warpHits2List(response.getHits());
    }

    /**
     * ??????????????????
     *
     * @return ??????id
     */
    public Pair<String, List<Webpage>> startScroll() {
        return startScroll(QueryBuilders.matchAllQuery(), 50);
    }

    /**
     * ??????????????????
     *
     * @param queryBuilder ????????????
     * @return ??????id????????????????????????
     */
    public Pair<String, List<Webpage>> startScroll(QueryBuilder queryBuilder, int size) {
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setQuery(queryBuilder)
                .setSize(size)
                .setScroll(TimeValue.timeValueMinutes(SCROLL_TIMEOUT));
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        return new MutablePair<>(response.getScrollId(), warpHits2List(response.getHits()));
    }

    /**
     * ????????????
     *
     * @param webpage ??????
     * @return
     */
    public boolean update(Webpage webpage) throws ExecutionException, InterruptedException {
        UpdateRequest updateRequest = new UpdateRequest();
        updateRequest.index(INDEX_NAME);
        updateRequest.type(TYPE_NAME);
        updateRequest.id(webpage.getId());
        updateRequest.doc(gson.toJson(webpage));
        UpdateResponse response = client.update(updateRequest).get();
        return response.getResult() == UpdateResponse.Result.UPDATED;
    }

    /**
     * ??????query???????????????
     *
     * @param query ??????queryString
     * @param size  ???????????????
     * @return ????????????
     */
    public Pair<Map<String, List<Terms.Bucket>>, List<Webpage>> relatedInfo(String query, int size) {
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setQuery(QueryBuilders.queryStringQuery(query))
                .addSort("gatherTime", SortOrder.DESC)
                .addAggregation(AggregationBuilders.terms("relatedPeople").field("namedEntity.nr"))
                .addAggregation(AggregationBuilders.terms("relatedLocation").field("namedEntity.ns"))
                .addAggregation(AggregationBuilders.terms("relatedInstitution").field("namedEntity.nt"))
                .addAggregation(AggregationBuilders.terms("relatedKeywords").field("keywords"))
                .setSize(size);
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        Map<String, List<Terms.Bucket>> info = Maps.newHashMap();
        info.put("relatedPeople", ((Terms) response.getAggregations().get("relatedPeople")).getBuckets());
        info.put("relatedLocation", ((Terms) response.getAggregations().get("relatedLocation")).getBuckets());
        info.put("relatedInstitution", ((Terms) response.getAggregations().get("relatedInstitution")).getBuckets());
        info.put("relatedKeywords", ((Terms) response.getAggregations().get("relatedKeywords")).getBuckets());
        return Pair.of(info, warpHits2List(response.getHits()));
    }

    /**
     * ??????????????????
     *
     * @param webpageList ????????????
     * @return
     */
    public boolean update(List<Webpage> webpageList) throws ExecutionException, InterruptedException {
        BulkRequestBuilder bulkRequest = client.prepareBulk();
        for (Webpage webpage : webpageList) {
            UpdateRequest updateRequest = new UpdateRequest();
            updateRequest.index(INDEX_NAME);
            updateRequest.type(TYPE_NAME);
            updateRequest.id(webpage.getId());
            updateRequest.doc(gson.toJson(webpage));
            bulkRequest.add(updateRequest);
        }
        BulkResponse bulkResponse = bulkRequest.get();
        return bulkResponse.hasFailures();
    }

    /**
     * ??????scrollId??????????????????
     *
     * @param scrollId scrollId
     * @return ????????????
     */
    public List<Webpage> scrollAllWebpage(String scrollId) {
        Preconditions.checkArgument(StringUtils.isNotBlank(scrollId), "scrollId????????????");
        SearchResponse response = client.prepareSearchScroll(scrollId)
                .setScroll(TimeValue.timeValueMinutes(SCROLL_TIMEOUT))
                .execute().actionGet();
        return warpHits2List(response.getHits());
    }

    /**
     * ?????????????????????????????????
     *
     * @return ??????-?????????
     */
    public Map<String, Long> countDomain(int size) {
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setQuery(QueryBuilders.matchAllQuery())
                .addAggregation(AggregationBuilders.terms("domain").field("domain").size(size).order(Terms.Order.count(false)));
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        Terms termsAgg = response.getAggregations().get("domain");
        List<Terms.Bucket> list = termsAgg.getBuckets();
        Map<String, Long> count = Maps.newLinkedHashMap();
        list.stream().filter(bucket -> ((String) bucket.getKey()).length() > 1).forEach(bucket -> {
            count.put((String) bucket.getKey(), bucket.getDocCount());
        });
        return count;
    }

    /**
     * ?????????????????????????????????????????????
     *
     * @param domain ????????????
     * @return ???-??????
     */
    public Map<String, Long> countWordByDomain(String domain) {
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setQuery(QueryBuilders.matchQuery("domain", domain))
                .addAggregation(AggregationBuilders.terms("content").field("content").size(200));
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        Terms termsAgg = response.getAggregations().get("content");
        List<Terms.Bucket> list = termsAgg.getBuckets();
        Map<String, Long> count = new HashMap<>();
        list.stream().filter(bucket -> ((String) bucket.getKey()).length() > 1).forEach(bucket -> {
            count.put((String) bucket.getKey(), bucket.getDocCount());
        });
        return count;
    }

    /**
     * ?????????????????????ID???????????????????????????
     *
     * @param id   ??????ID
     * @param size ????????????
     * @param page ??????
     * @return
     */
    public List<Webpage> moreLikeThis(String id, int size, int page) {
        MoreLikeThisQueryBuilder.Item[] items = {new MoreLikeThisQueryBuilder.Item(INDEX_NAME, TYPE_NAME, id)};
        String[] fileds = {"content"};
        SearchResponse response = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(moreLikeThisQuery(fileds, null, items)
                        .minTermFreq(1)
                        .maxQueryTerms(12))
                .setSize(size).setFrom(size * (page - 1))
                .execute()
                .actionGet();
        return warpHits2List(response.getHits());
    }

    /**
     * ????????????????????????????????????
     *
     * @param domain ????????????
     * @return
     */
    public Map<Date, Long> countDomainByGatherTime(String domain) {
        AggregationBuilder aggregation =
                AggregationBuilders
                        .dateHistogram("agg")
                        .field("gatherTime")
                        .dateHistogramInterval(DateHistogramInterval.DAY).order(Histogram.Order.KEY_DESC);
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME)
                .setQuery(QueryBuilders.matchQuery("domain", domain))
                .addAggregation(aggregation);
        SearchResponse response = searchRequestBuilder.execute().actionGet();
        Histogram agg = response.getAggregations().get("agg");
        Map<Date, Long> result = Maps.newHashMap();
        for (Histogram.Bucket entry : agg.getBuckets()) {
            DateTime key = (DateTime) entry.getKey();    // Key
            long docCount = entry.getDocCount();         // Doc count
            result.put(key.toDate(), docCount);
        }
        return result;
    }

    /**
     * ????????????domain????????????
     *
     * @param domain ????????????
     * @param task   ????????????
     * @return ??????????????????????????????
     */
    public boolean deleteByDomain(String domain, Task task) {
        return deleteByQuery(QueryBuilders.matchQuery("domain", domain), task);
    }

    /**
     * ????????????????????????????????????
     *
     * @param query
     * @param domain
     * @param size
     * @param page
     * @return
     */
    public Pair<List<Webpage>, Long> getWebpageByKeywordAndDomain(String query, String domain, int size, int page) {
        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(INDEX_NAME)
                .setTypes(TYPE_NAME);
        QueryBuilder keyWorkQuery, domainQuery;
        if (StringUtils.isBlank(query)) {
            query = "*";
        }
        keyWorkQuery = QueryBuilders.queryStringQuery(query).analyzer("query_ansj").defaultField("content");
        if (StringUtils.isBlank(domain)) {
            domain = "*";
        } else {
            domain = "*" + domain + "*";
        }
        domainQuery = QueryBuilders.queryStringQuery(domain).field("domain");

        searchRequestBuilder.setQuery(keyWorkQuery)
                .setPostFilter(domainQuery)
                .setSize(size).setFrom(size * (page - 1));
        SearchHits searchHits = searchRequestBuilder.get().getHits();
        return Pair.of(warpHits2List(searchHits), searchHits.getTotalHits());
    }
}
