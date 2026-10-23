package com.github.gcd888.guoju.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.gcd888.guoju.bean.ConfigNodeBean;

import org.junit.Test;

import java.util.Set;

/**
 * ConfigRepo.parseNodes 纯 JVM 单测（零 Android 依赖）：
 * 覆盖数据段探测（sites/lives/dramas/musics）、终端判型放宽、仓库/线路层级与无法识别格式。
 */
public class ConfigRepoTest {

    @Test
    public void onlyLivesAndDramas_isTerminal() throws Exception {
        String json = "{\"lives\":[{\"name\":\"l\",\"url\":\"http://l\"}],"
                + "\"dramas\":[{\"key\":\"d1\",\"type\":1,\"api\":\"http://d\"}]}";
        ConfigRepo.FetchResult r = ConfigRepo.parseNodes(json, "http://cfg");
        assertEquals(ConfigNodeBean.TYPE_TERMINAL, r.type);
        assertTrue(r.sections.contains(ConfigRepo.SECTION_LIVES));
        assertTrue(r.sections.contains(ConfigRepo.SECTION_DRAMAS));
        assertFalse(r.sections.contains(ConfigRepo.SECTION_SITES));
        assertTrue(r.nodes.isEmpty());
    }

    @Test
    public void sitesLivesDramas_allSections() throws Exception {
        String json = "{\"sites\":[{\"key\":\"s\",\"type\":3,\"api\":\"csp_X\"}],"
                + "\"lives\":[],\"dramas\":[],\"spider\":\"http://spider.jar\"}";
        ConfigRepo.FetchResult r = ConfigRepo.parseNodes(json, "http://cfg");
        assertEquals(ConfigNodeBean.TYPE_TERMINAL, r.type);
        assertEquals(3, r.sections.size());
        assertTrue(r.sections.contains(ConfigRepo.SECTION_SITES));
    }

    @Test
    public void musicsOnly_isTerminal() throws Exception {
        String json = "{\"musics\":[{\"name\":\"m\",\"url\":\"http://m\"}]}";
        ConfigRepo.FetchResult r = ConfigRepo.parseNodes(json, "http://cfg");
        assertEquals(ConfigNodeBean.TYPE_TERMINAL, r.type);
        Set<String> sections = r.sections;
        assertEquals(1, sections.size());
        assertTrue(sections.contains(ConfigRepo.SECTION_MUSICS));
    }

    @Test
    public void storeHouse_isWarehouseWithNodes() throws Exception {
        String json = "{\"storeHouse\":[{\"sourceName\":\"仓A\",\"sourceUrl\":\"http://a\"},"
                + "{\"sourceUrl\":\"http://b\"},{\"sourceName\":\"空\",\"sourceUrl\":\"\"}]}";
        ConfigRepo.FetchResult r = ConfigRepo.parseNodes(json, "http://cfg");
        assertEquals(ConfigNodeBean.TYPE_WAREHOUSE, r.type);
        assertEquals(2, r.nodes.size());
        assertEquals("仓A", r.nodes.get(0).name);
        assertEquals("http://b", r.nodes.get(1).name); // 缺名回填 url
    }

    @Test
    public void urls_isLineWithNodes() throws Exception {
        String json = "{\"urls\":[{\"name\":\"线1\",\"url\":\"http://1\"}]}";
        ConfigRepo.FetchResult r = ConfigRepo.parseNodes(json, "http://cfg");
        assertEquals(ConfigNodeBean.TYPE_LINE, r.type);
        assertEquals(1, r.nodes.size());
        assertEquals("线1", r.nodes.get(0).name);
        assertEquals(ConfigNodeBean.TYPE_LINE, r.nodes.get(0).type);
    }

    @Test
    public void unknownFormat_throws() {
        try {
            ConfigRepo.parseNodes("{\"foo\":1}", "http://cfg");
            fail("应抛 JSONException");
        } catch (org.json.JSONException expected) {
            // ok
        }
    }

    @Test
    public void blankContent_throws() {
        try {
            ConfigRepo.parseNodes("  ", "http://cfg");
            fail("应抛 JSONException");
        } catch (org.json.JSONException expected) {
            // ok
        }
    }
}
