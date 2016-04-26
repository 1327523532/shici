package com.itranswarp.shici.search;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.itranswarp.compiler.JavaStringCompiler;
import com.itranswarp.shici.util.HttpUtil;
import com.itranswarp.shici.util.HttpUtil.HttpResponse;
import com.itranswarp.shici.util.JsonUtil;
import com.itranswarp.shici.util.MapUtil;
import com.itranswarp.shici.util.ValidateUtil;

@Component
public class Searcher {

	final Log log = LogFactory.getLog(getClass());

	static final Map<String, String> JSON_HEADERS = MapUtil.createMap("Content-Type", "application/json");

	@Value("${es.url}")
	String esUrl;

	// search /////////////////////////////////////////////////////////////////

	/**
	 * Search by query string.
	 * 
	 * @param indexName Index name.
	 * @param clazz Document type.
	 * @param qs Query string as array.
	 * @param maxResults The max results.
	 * @return List that contains documents.
	 */
	public <T extends Searchable> List<T> search(String indexName, Class<T> clazz, String[] qs, int maxResults) {
		// build query:
		Map<String, Object> query = buildQuery(qs);
		query.put("from", 0);
		query.put("size", maxResults);
		log.info("Query: " + JsonUtil.toJson(query));
		double minScore = 0.0;
		Class<HitsResultWrapper<T>> hitsResultWrapperClass = getHitsResultWrapperClass(clazz);
		HitsResultWrapper<T> hitsResultWrapper = postJSON(hitsResultWrapperClass,
				indexName + "/" + clazz.getSimpleName() + "/_search", query);
		HitsWrapper<T> hitsWrapper = hitsResultWrapper.getHitsWrapper();
		int total = hitsWrapper.getTotal();
		if (total == 0) {
			return Collections.emptyList();
		}
		List<? extends DocumentWrapper<T>> list = hitsWrapper.getDocumentWrappers();
		List<T> results = new ArrayList<T>(list.size());
		for (DocumentWrapper<T> dw : list) {
			if (dw.getScore() > minScore) {
				results.add(dw.getDocument());
			}
		}
		return results;
	}

	Map<String, Object> buildQuery(String[] qs) {
		if (qs.length == 1) {
			return buildSingleQuery(qs[0]);
		}
		return buildBoolShouldQuery(qs);
	}

	// build: { "query": { "bool": { "should": [ ... ] } } }
	Map<String, Object> buildBoolShouldQuery(String[] qs) {
		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		int len = qs.length;
		if (len > 3) {
			len = 3;
		}
		for (int i = 0; i < len; i++) {
			list.add(buildSubQuery(qs[i]));
		}
		return MapUtil.createMap("query", MapUtil.createMap("bool", MapUtil.createMap("should", list)));
	}

	Map<String, Object> buildSubQuery(String q) {
		if (q.length() == 1) {
			return buildTermQuery(q);
		}
		if (q.length() <= 3) {
			return buildPhraseQuery(q);
		}
		return buildFuzzyPhraseQuery(q);
	}

	/**
	 * "bool": { "should": [ { "match_phrase" : { "_all" : { "query": "日照香炉升紫烟",
	 * "slop": 1 } } }, { "multi_match": { "fields": ["title", "message"],
	 * "query": "日照香炉升紫烟", "minimum_should_match": "60%", "fuzziness": 0 } } ] }
	 */
	Map<String, Object> buildFuzzyPhraseQuery(String q) {
		if (q.length() > 7) {
			q = q.substring(0, 7);
		}
		List<Map<String, Object>> shoulds = new ArrayList<Map<String, Object>>(2);
		shoulds.add(buildPhraseQuery(q));
		shoulds.add(MapUtil.createMap("multi_match",
				MapUtil.createMap("fields", "_all", "query", q, "minimum_should_match", "75%")));
		return MapUtil.createMap("bool", MapUtil.createMap("should", shoulds));
	}

	// build: { "match_phrase": { "_all": { "query": "xxx", "slop": 1 } } }
	Map<String, Object> buildPhraseQuery(String q) {
		return MapUtil.createMap("match_phrase", MapUtil.createMap("_all", MapUtil.createMap("query", q, "slop", 1)));
	}

	// build: { "query": {...} }
	Map<String, Object> buildSingleQuery(String q) {
		if (q.length() == 1) {
			return MapUtil.createMap("query", buildTermQuery(q));
		}
		if (q.length() <= 3) {
			return MapUtil.createMap("query", buildPhraseQuery(q));
		}
		return MapUtil.createMap("query", buildFuzzyPhraseQuery(q));
	}

	// build: { "term": {...} }
	Map<String, Object> buildTermQuery(String term) {
		return MapUtil.createMap("term", MapUtil.createMap("_all", MapUtil.createMap("value", term, "boost", 0.12)));
	}

	// document ///////////////////////////////////////////////////////////////

	public <T extends Searchable> void createMapping(String indexName, Class<T> clazz) {
		Map<String, Map<String, String>> properties = this.createMapping(clazz);
		putJSON(Map.class, indexName + "/_mapping/" + clazz.getSimpleName(),
				MapUtil.createMap("properties", properties));
	}

	public <T extends Searchable> void createDocument(String indexName, T doc) {
		ValidateUtil.checkId(doc.getId());
		putJSON(Map.class, indexName + "/" + doc.getClass().getSimpleName() + "/" + doc.getId(), doc);
	}

	public <T extends Searchable> T getDocument(String indexName, Class<T> clazz, String id) {
		ValidateUtil.checkId(id);
		Class<DocumentWrapper<T>> wrapperClass = getDocumentWrapperClass(clazz);
		DocumentWrapper<T> wrapper = getJSON(wrapperClass, indexName + "/" + clazz.getSimpleName() + "/" + id);
		return wrapper.getDocument();
	}

	public <T extends Searchable> void deleteDocument(String indexName, Class<T> clazz, String id) {
		ValidateUtil.checkId(id);
		deleteJSON(Map.class, indexName + "/" + clazz.getSimpleName() + "/" + id, null);
	}

	// index //////////////////////////////////////////////////////////////////

	/**
	 * Check if index exist.
	 * 
	 * @param name Index name.
	 * @return True if index exist, otherwise false.
	 */
	public boolean indexExist(String name) {
		try {
			getJSON(Map.class, name);
		} catch (SearchResultException e) {
			return false;
		}
		return true;
	}

	/**
	 * Create new index.
	 * 
	 * @param name Index name.
	 */
	public void createIndex(String name) {
		putJSON(Map.class, name, null);
	}

	/**
	 * Delete index.
	 * 
	 * @param name Index name.
	 */
	public void deleteIndex(String name) {
		deleteJSON(Map.class, name, null);
	}

	// helper /////////////////////////////////////////////////////////////////

	Map<String, List<String>> searchableFieldsCache = new ConcurrentHashMap<String, List<String>>();

	List<String> getSearchableFields(Class<?> clazz) {
		List<String> list = searchableFieldsCache.get(clazz.getName());
		if (list == null) {
			list = new ArrayList<String>();
			for (Field f : clazz.getFields()) {
				if (f.isAnnotationPresent(Analyzed.class)) {
					list.add(f.getName());
				}
			}
			searchableFieldsCache.put(clazz.getName(), list);
		}
		return list;
	}

	Map<String, Map<String, String>> createMapping(Class<?> clazz) {
		log.info("Building mapping for class: " + clazz.getName());
		Map<String, Map<String, String>> properties = new HashMap<String, Map<String, String>>();
		for (Field f : clazz.getFields()) {
			if (f.isAnnotationPresent(Analyzed.class)) {
				properties.put(f.getName(), getMappingProperty(f.getType(), true));
			} else {
				Map<String, String> mapping = getMappingProperty(f.getType(), false);
				if (mapping != null) {
					properties.put(f.getName(), mapping);
				} else {
					log.info("Ignore unsupported field: " + f.getName());
				}
			}
		}
		return properties;
	}

	Map<String, String> getMappingProperty(Class<?> clazz, boolean analyzed) {
		String type;
		switch (clazz.getName()) {
		case "java.lang.String":
			type = "string";
			break;
		case "int":
		case "java.lang.Integer":
			type = "integer";
			break;
		case "long":
		case "java.lang.Long":
			type = "long";
			break;
		case "float":
		case "java.lang.Float":
			type = "float";
			break;
		case "double":
		case "java.lang.Double":
			type = "double";
			break;
		default:
			throw new IllegalArgumentException("Type " + clazz.getName() + " is not supported.");
		}
		return MapUtil.createMap("type", type, "index", analyzed ? "analyzed" : "not_analyzed");
	}

	<T> T getJSON(Class<T> clazz, String path) {
		log.info("GET: " + esUrl + path);
		try {
			HttpResponse resp = HttpUtil.httpGet(esUrl + path, null, null);
			return checkResponse(resp, clazz);
		} catch (IOException e) {
			throw new SearchException(e);
		}
	}

	<T> T postJSON(Class<T> clazz, String path, Object data) {
		log.info("POST: " + esUrl + path);
		try {
			HttpResponse resp = HttpUtil.httpPost(esUrl + path, data == null ? null : JsonUtil.toJson(data),
					JSON_HEADERS);
			return checkResponse(resp, clazz);
		} catch (IOException e) {
			throw new SearchException(e);
		}
	}

	<T> T putJSON(Class<T> clazz, String path, Object data) {
		log.info("PUT: " + esUrl + path);
		try {
			HttpResponse resp = HttpUtil.httpPut(esUrl + path, data == null ? null : JsonUtil.toJson(data),
					JSON_HEADERS);
			return checkResponse(resp, clazz);
		} catch (IOException e) {
			throw new SearchException(e);
		}
	}

	<T> T deleteJSON(Class<T> clazz, String path, Object data) {
		log.info("DELETE: " + esUrl + path);
		try {
			HttpResponse resp = HttpUtil.httpDelete(esUrl + path, data == null ? null : JsonUtil.toJson(data),
					JSON_HEADERS);
			return checkResponse(resp, clazz);
		} catch (IOException e) {
			throw new SearchException(e);
		}
	}

	<T> T checkResponse(HttpResponse resp, Class<T> clazz) {
		if (resp.isOK()) {
			log.info("Response: " + resp.body);
			return JsonUtil.fromJson(clazz, resp.body);
		}
		log.info("Error Response: " + resp.body);
		String jsonErr = resp.body;
		if (jsonErr == null) {
			jsonErr = "{}";
		}
		throw JsonUtil.fromJson(SearchResultException.class, jsonErr);
	}

	// on-the-fly compiler ////////////////////////////////////////////////////

	Map<String, Class<?>> hitsMap = new ConcurrentHashMap<String, Class<?>>();

	Map<String, Class<?>> docMap = new ConcurrentHashMap<String, Class<?>>();

	@SuppressWarnings("unchecked")
	<T extends Searchable> Class<HitsResultWrapper<T>> getHitsResultWrapperClass(Class<T> clazz) {
		String key = clazz.getName();
		Class<?> value = hitsMap.get(key);
		if (value == null) {
			value = compile(new HitsResultWrapperSourceBuilder(), clazz);
			hitsMap.put(key, value);
		}
		return (Class<HitsResultWrapper<T>>) value;
	}

	@SuppressWarnings("unchecked")
	<T extends Searchable> Class<DocumentWrapper<T>> getDocumentWrapperClass(Class<T> clazz) {
		String key = clazz.getName();
		Class<?> value = docMap.get(key);
		if (value == null) {
			value = compile(new DocumentWrapperSourceBuilder(), clazz);
			docMap.put(key, value);
		}
		return (Class<DocumentWrapper<T>>) value;
	}

	Class<?> compile(SourceBuilder builder, Class<?> clazz) {
		try {
			JavaStringCompiler compiler = new JavaStringCompiler();
			Map<String, byte[]> results = compiler.compile(builder.getFileName(clazz), builder.createSource(clazz));
			return compiler.loadClass(builder.getClassName(clazz), results);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
