package com.seolpc.seoltube.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.Extractor;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.InfoItemsCollector;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.feed.FeedInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * seoltube 엔진의 문 하나.
 * 앱은 이 클래스를 이름으로 불러 call(요청 JSON) 만 부른다. 앱과 엔진 사이에 같은 클래스를 나눠 갖지 않으므로
 * NewPipe 가 안쪽을 바꿔도 앱은 다시 깔 필요가 없다.
 *
 * 요청 {"m":"init","cache":폴더} {"m":"version"} {"m":"audio","id":"영상ID"} {"m":"streams","id":..} {"m":"search","q":..} {"m":"related","id":..} {"m":"suggest","q":..} {"m":"music","q":..,"part":1|2} {"m":"shorts","token"?,"vd"?,"cv"?,"seed"?} {"m":"shortsSearch","q":..}
 * {"m":"comments","id":..,"page"?:{..}} (page 는 앞 응답의 next 또는 댓글의 replies 를 그대로)
 * {"m":"heatmap","id":..} 많이 본 구간
 * {"m":"channel","url":채널 주소,"tab":"videos"|"shorts","sort"?:"popular","cid"?,"page"?} 채널(처음: 이름, 그림, 구독자 + 목록. 다음: cid 와 page 로 목록만)
 * {"m":"feed","channels":[채널 주소...]} 구독한 채널들의 새 영상(RSS)
 * {"m":"radio","id":..,"page"?} 노래 라디오(유튜브 뮤직처럼 이 곡에서 이어지는 노래들, 자동 믹스 RDAMVM)
 * 응답 {"ok":true, ...} 또는 {"ok":false,"error":"..."}
 * 응답에 칸을 더하는 것은 API 를 올리지 않는다(앱은 모르는 칸을 무시). 있던 칸을 바꾸거나 뺄 때만 올린다.
 */
public final class Engine {
    /** 앱과 약속한 문의 판. 요청/응답 모양을 바꾸면 올린다. */
    public static final int API = 1;

    private static boolean inited;

    public Engine() {
        synchronized (Engine.class) {
            if (!inited) {
                NewPipe.init(new HttpDownloader(), new Localization("ko", "KR"), new ContentCountry("KR"));
                inited = true;
            }
        }
    }

    public String call(final String request) {
        try {
            final JSONObject q = new JSONObject(request);
            final String m = q.optString("m");
            final JSONObject r;
            switch (m) {
                case "version": r = version(); break;
                case "init": r = init(q); break;
                case "audio": r = audio(q.getString("id")); break;
                case "streams": r = streams(q.getString("id")); break;
                case "search": r = search(q.getString("q")); break;
                case "related": r = related(q.getString("id")); break;
                case "suggest": r = suggest(q.getString("q")); break;
                case "music": r = music(q.getString("q"), q.optInt("part", 0)); break;
                case "shorts": r = shortsFeed(q); break;
                case "shortsSearch": r = shortsSearch(q.getString("q")); break;
                case "comments": r = comments(q); break;
                case "heatmap": r = heatmap(q.getString("id")); break;
                case "channel": r = channel(q); break;
                case "feed": r = feed(q); break;
                case "radio": r = radio(q); break;
                default: throw new IllegalArgumentException("모르는 요청: " + m);
            }
            return r.put("ok", true).toString();
        } catch (Throwable t) {
            try {
                return new JSONObject().put("ok", false)
                        .put("error", t.getClass().getSimpleName() + ": " + t.getMessage()).toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"?\"}";
            }
        }
    }

    /**
     * 한국어로 묻기(형님 2026-11-13 "한국 영상인데 가끔 제목이 영어로 나온다"). NewPipe 의 YouTube 는 en-GB 만 받아 주므로
     * (NewPipe.init 의 ko-KR 은 무시되고 en-GB 로 묻는다) 유튜브가 제목을 영어로 자동 번역해 보냈다. 묻는 것마다 ko-KR 로 못 박는다.
     * NewPipe 는 한국어 "5시간 전" 은 잘 읽지만 줄인 수 "조회수 7.4만회" 는 74 로 읽어서, HttpDownloader 가 응답의 줄인 수를
     * "조회수 74000회" 처럼 풀어 준다.
     */
    private static final Localization KO = new Localization("ko", "KR");
    private static final ContentCountry KR = new ContentCountry("KR");

    private static <T extends Extractor> T ko(final T e) {
        e.forceLocalization(KO);
        e.forceContentCountry(KR);
        return e;
    }

    /** ko 로 못 박고 바로 받아 둔다(SearchInfo, ChannelInfo, ChannelTabInfo 의 getInfo(extractor) 는 스스로 받지 않음) */
    private static <T extends Extractor> T koFetched(final T e) throws Exception {
        ko(e).fetchPage();
        return e;
    }

    private static StreamingService yt() {
        return ServiceList.YouTube;
    }

    private static String watchUrl(final String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }

    /** 앱이 처음 부를 때: 캐시 폴더 알려 주기(재생 스크립트 캐시) */
    private static JSONObject init(final JSONObject q) throws Exception {
        final String dir = q.optString("cache", "");
        if (!dir.isEmpty()) HttpDownloader.jsCacheDir = new File(dir);
        return new JSONObject();
    }

    private static JSONObject version() throws Exception {
        return new JSONObject().put("api", API).put("newpipe", BuildConfig.NEWPIPE);
    }

    /** 받기용: 한 번에 받을 수 있는(PROGRESSIVE) 소리 가운데 원래 언어, 가장 높은 음질 하나 */
    private static JSONObject audio(final String id) throws Exception {
        final StreamExtractor ex = ko(yt().getStreamExtractor(watchUrl(id)));
        final StreamInfo info = StreamInfo.getInfo(ex);
        AudioStream best = null;
        for (AudioStream a : info.getAudioStreams()) {
            if (!a.isUrl() || a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            if (a.getAudioTrackType() != null) {
                final String t = a.getAudioTrackType().name();
                if (t.equals("DUBBED") || t.equals("DESCRIPTIVE")) continue;
            }
            if (best == null || a.getAverageBitrate() > best.getAverageBitrate()) best = a;
        }
        if (best == null) throw new IllegalStateException("받을 수 있는 소리가 없습니다");
        final JSONObject r = base(info);
        final String lt = localTitle(ex);
        if (!lt.isEmpty()) r.put("title", lt);
        final JSONObject s = new JSONObject()
                .put("url", best.getContent())
                .put("mime", best.getFormat() == null ? "" : best.getFormat().getMimeType())
                .put("ext", best.getFormat() == null ? "" : best.getFormat().getSuffix())
                .put("kbps", best.getAverageBitrate())
                .put("codec", best.getCodec() == null ? "" : best.getCodec())
                .put("size", best.getItagItem() == null ? -1 : best.getItagItem().getContentLength());
        return r.put("audio", s);
    }

    /** 영상 보기용: 영상만(높은 화질), 소리만, 둘 다 든 것(낮은 화질). 제목을 누르면 보는 자세한 정보(날짜, 좋아요, 설명, 채널)도 */
    private static JSONObject streams(final String id) throws Exception {
        final StreamExtractor ex = ko(yt().getStreamExtractor(watchUrl(id)));
        final StreamInfo info = StreamInfo.getInfo(ex);
        final JSONObject r = base(info);
        final String lt = localTitle(ex);
        if (!lt.isEmpty()) r.put("title", lt);
        details(info, r);
        final JSONArray vo = new JSONArray();
        for (VideoStream v : info.getVideoOnlyStreams()) {
            if (!v.isUrl()) continue;
            vo.put(new JSONObject().put("url", v.getContent()).put("h", v.getHeight())
                    .put("fps", v.getFps()).put("mime", v.getFormat() == null ? "" : v.getFormat().getMimeType())
                    .put("codec", v.getCodec() == null ? "" : v.getCodec())
                    .put("progressive", v.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP));
        }
        final JSONArray mx = new JSONArray();
        for (VideoStream v : info.getVideoStreams()) {
            if (!v.isUrl()) continue;
            mx.put(new JSONObject().put("url", v.getContent()).put("h", v.getHeight())
                    .put("mime", v.getFormat() == null ? "" : v.getFormat().getMimeType())
                    .put("progressive", v.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP));
        }
        final JSONArray au = new JSONArray();
        for (AudioStream a : info.getAudioStreams()) {
            if (!a.isUrl()) continue;
            au.put(new JSONObject().put("url", a.getContent()).put("kbps", a.getAverageBitrate())
                    .put("mime", a.getFormat() == null ? "" : a.getFormat().getMimeType())
                    .put("codec", a.getCodec() == null ? "" : a.getCodec())
                    .put("track", a.getAudioTrackType() == null ? "" : a.getAudioTrackType().name())
                    .put("progressive", a.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP));
        }
        // 관련 영상도 같이(같은 정보로 한 번에. 따로 related 를 부르면 같은 영상을 두 번 읽는다)
        return r.put("videoOnly", vo).put("muxed", mx).put("audio", au)
                .put("related", items(info.getRelatedItems()))
                .put("dash", info.getDashMpdUrl() == null ? "" : info.getDashMpdUrl())
                .put("hls", info.getHlsUrl() == null ? "" : info.getHlsUrl());
    }

    private static JSONObject search(final String query) throws Exception {
        final SearchInfo info = SearchInfo.getInfo(koFetched(yt().getSearchExtractor(
                yt().getSearchQHFactory().fromQuery(query, Collections.singletonList("videos"), ""))));
        return new JSONObject().put("items", items(info.getRelatedItems()));
    }

    /**
     * 음악 검색: 일반 유튜브 검색 결과를 모두(쇼츠만 뺌) 돌려주고 공식 음원이면 official=true(isOfficialAudio).
     * 한국은 로그인 없이 유튜브 뮤직의 '노래' 검색이 막혀 있어서 이렇게 한다(2026-11-10 확인).
     * part: 1 = 검색어 그대로(영상만, 인기순에 가까움), 2 = 검색어 + " auto-generated"(음원이 많이 나옴).
     * 앱은 1 과 2 를 따로 동시에 불러 먼저 온 것부터 보여 준다(형님 2026-11-10 "음악 검색이 검색 중에서 너무 오래 걸린다").
     * 엔진 6 전의 앱이 부르던 part 0(둘을 합쳐 공식 음원만)은 뺐다. part 가 2 가 아니면 1 로 본다.
     */
    private static JSONObject music(final String query, final int part) throws Exception {
        // 형님 2026-11-10: "라이브, 가사 영상도 나와도 되는데 순위가 낮아서 아래에. 아예 거르라는 게 아니라 음원을 다 띄우고 그다음 아래에"
        // → 거르지 않고 모두 돌려주되 공식 음원이면 official=true. 앱이 공식 음원을 먼저, 나머지를 아래에 놓는다
        final LinkedHashMap<String, JSONObject> out = new LinkedHashMap<>();
        addAll(videoSearch(part == 2 ? query + " auto-generated" : query), out);
        return new JSONObject().put("items", new JSONArray(out.values()));
    }

    /**
     * 공식 음원으로 볼 것(official=true, 앱이 먼저 놓는다. 형님 2026-11-10 "딱 봐도 공식 음원, 영상 없는 앨범 재킷만 있는 음원, 또는 official audio 같은 것들만"):
     * ① 음반사가 올린 공식 음원(설명이 "Provided to YouTube by" 로 시작, 앨범 재킷만 나오는 것)
     * ② 제목에 Official Audio, (Audio), [Audio] 가 붙은 것
     */
    private static boolean isOfficialAudio(final StreamInfoItem s) throws Exception {
        final String d = s.getShortDescription();
        if (d != null && d.startsWith("Provided to YouTube by")) return true;
        final String t = s.getName() == null ? "" : s.getName().toLowerCase(Locale.ROOT);
        return t.contains("official audio") || t.contains("(audio)") || t.contains("[audio]");
    }

    private static List<InfoItem> videoSearch(final String q) throws Exception {
        return SearchInfo.getInfo(koFetched(yt().getSearchExtractor(
                yt().getSearchQHFactory().fromQuery(q, Collections.singletonList("videos"), "")))).getRelatedItems();
    }

    /** 검색 결과 모두(쇼츠는 뺌), 공식 음원 표시(official) 붙여서 */
    private static void addAll(final List<InfoItem> list, final Map<String, JSONObject> out) throws Exception {
        for (InfoItem it : list) {
            if (!(it instanceof StreamInfoItem)) continue;
            final StreamInfoItem s = (StreamInfoItem) it;
            if (s.isShortFormContent()) continue;
            final String id;
            try {
                id = yt().getStreamLHFactory().getId(s.getUrl());
            } catch (Exception e) {
                continue;
            }
            if (out.containsKey(id)) continue;
            final boolean official = isOfficialAudio(s);
            String up = s.getUploaderName() == null ? "" : s.getUploaderName();
            if (up.endsWith(" - Topic")) up = up.substring(0, up.length() - 8);
            out.put(id, new JSONObject()
                    .put("id", id)
                    .put("title", s.getName())
                    .put("uploader", up)
                    .put("duration", s.getDuration())
                    .put("views", s.getViewCount())
                    .put("official", official)
                    .put("thumb", bestThumb(s.getThumbnails())));
        }
    }

    // ---------- 쇼츠 피드 ----------
    // 로그인 안 한 유튜브 웹이 주는 쇼츠 그대로(형님 2026-11-11 "진짜 쇼츠 그 스크롤하는 그걸 보자는 거야. 쇼츠 검색을 왜 하니").
    // 처음: youtube.com/shorts/(또는 /shorts/<seed>) 쪽에서 첫 쇼츠, 이어 받기 표(sequenceContinuation), visitorData, 웹 판을 읽고
    // reel/reel_watch_sequence 로 다음 쇼츠들. 이어서: 앱이 돌려준 표로 다음 묶음. 쇼츠 ID 만 돌려주고 제목 등은 streams 로.

    private static final Pattern P_VID = Pattern.compile("\"videoId\":\"([\\w-]{11})\"");
    private static final Pattern P_SEQ = Pattern.compile("\"sequenceContinuation\":\"([^\"]+)\"");
    private static final Pattern P_SP = Pattern.compile("\"sequenceParams\":\"([^\"]+)\"");
    private static final Pattern P_VD = Pattern.compile("\"visitorData\":\"([^\"]+)\"");
    private static final Pattern P_CV = Pattern.compile("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"");

    private static JSONObject shortsFeed(final JSONObject q) throws Exception {
        String token = q.optString("token", "");
        String vd = q.optString("vd", "");
        String cv = q.optString("cv", "");
        final JSONArray ids = new JSONArray();
        if (token.isEmpty()) {
            final String seed = q.optString("seed", "");
            String[] st;
            try {
                st = shortsStart(seed);
            } catch (Exception e) {
                // 출발 쇼츠의 쪽이 안 되면(지워졌거나 비공개가 된 좋아요 쇼츠 등) 출발 쇼츠 없이 한 번 더
                if (seed.isEmpty()) throw e;
                st = shortsStart("");
            }
            if (!seed.isEmpty()) ids.put(seed);          // 누른 쇼츠가 맨 앞(앱이 처음 것을 출발 쇼츠로 본다)
            else if (!st[0].isEmpty()) ids.put(st[0]);
            token = st[1];
            if (!st[2].isEmpty()) vd = st[2];
            cv = st[3];
        }
        final JSONObject body = new JSONObject().put("context", webContext(cv, vd)).put("sequenceParams", token);
        final JSONObject r = new JSONObject(postJson(YT_API + "reel/reel_watch_sequence?prettyPrint=false", body));
        final JSONArray entries = r.optJSONArray("entries");
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) seen.add(ids.getString(i));
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            final JSONObject cmd = entries.optJSONObject(i) == null ? null : entries.optJSONObject(i).optJSONObject("command");
            final JSONObject ep = cmd == null ? null : cmd.optJSONObject("reelWatchEndpoint");
            final String id = ep == null ? "" : ep.optString("videoId", "");
            // 광고는 뺀다(형님 2026-11-12 "왜 자꾸 니베아 SOS 로션이 나오냐"): 로그인 없는 쇼츠 피드는 묶음마다 광고 한 칸을 끼우고,
            // 그 칸의 reelWatchEndpoint 에는 adClientParams 가 있다(PC 확인: NIVEA Korea 쇼츠가 늘 세 번째 칸)
            if (ep == null || ep.has("adClientParams") || entries.optJSONObject(i).toString().contains("\"adSlotRenderer\"")) continue;
            if (!id.isEmpty() && seen.add(id)) ids.put(id);
        }
        String next = "";
        final JSONObject ce = r.optJSONObject("continuationEndpoint");
        if (ce != null && ce.optJSONObject("continuationCommand") != null) {
            next = ce.optJSONObject("continuationCommand").optString("token", "");
        }
        return new JSONObject().put("items", ids).put("next", next).put("vd", vd).put("cv", cv);
    }

    /** 쇼츠 첫 쪽(seed 가 비면 무작위)에서 {첫 쇼츠 ID, 이어 받기 표, visitorData, 웹 판}. 표가 없으면 예외 */
    private static String[] shortsStart(final String seed) throws Exception {
        final Map<String, List<String>> h = new HashMap<>();
        h.put("User-Agent", Collections.singletonList(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"));
        h.put("Accept-Language", Collections.singletonList("ko-KR,ko;q=0.9"));
        final String html = NewPipe.getDownloader().get(
                "https://www.youtube.com/shorts/" + seed + "?hl=ko&gl=KR", h).responseBody();
        Matcher m = P_VID.matcher(html);
        final String first = m.find() ? m.group(1) : "";
        // 무작위 출발은 sequenceContinuation, 쇼츠 하나에서 출발하면 sequenceParams(그 쇼츠와 비슷한 것들)
        final String token;
        m = P_SEQ.matcher(html);
        if (m.find()) {
            token = m.group(1);
        } else {
            final Matcher m2 = P_SP.matcher(html);
            if (!m2.find()) throw new IllegalStateException("쇼츠 피드를 찾지 못했습니다");
            token = m2.group(1);
        }
        m = P_VD.matcher(html);
        final String vd = m.find() ? m.group(1) : "";
        m = P_CV.matcher(html);
        final String cv = m.find() ? m.group(1) : WEB_VERSION;
        return new String[]{first, token, vd, cv};
    }

    // ---------- 유튜브 웹(youtubei) 직접 요청: 쇼츠 피드, 쇼츠 검색, 많이 본 구간, 채널 인기순 ----------

    private static final String YT_API = "https://www.youtube.com/youtubei/v1/";
    /** 직접 묻는 요청의 유튜브 웹 판(쇼츠 첫 쪽에서 읽은 판이 있으면 그것을 쓴다) */
    private static final String WEB_VERSION = "2.20261106.00.00";

    /** 한국어, 한국으로 묻는 WEB 클라이언트 context. vd(visitorData)는 있으면 */
    private static JSONObject webContext(final String clientVersion, final String vd) throws Exception {
        final JSONObject client = new JSONObject().put("clientName", "WEB").put("clientVersion", clientVersion)
                .put("hl", "ko").put("gl", "KR");
        if (vd != null && !vd.isEmpty()) client.put("visitorData", vd);
        return new JSONObject().put("client", client);
    }

    /** JSON 몸을 POST 하고 응답 글을 그대로(NewPipe 의 HttpDownloader 를 거치므로 줄인 수 풀기도 같음) */
    private static String postJson(final String url, final JSONObject body) throws Exception {
        final Map<String, List<String>> h = new HashMap<>();
        h.put("Content-Type", Collections.singletonList("application/json"));
        h.put("Origin", Collections.singletonList("https://www.youtube.com"));
        return NewPipe.getDownloader().post(url, h, body.toString().getBytes(StandardCharsets.UTF_8)).responseBody();
    }

    /**
     * 쇼츠 검색(형님 2026-11-11 "쇼츠도 검색해서 보게"): 유튜브 웹 검색의 Shorts 걸러보기(params EgIQCQ==) 결과에서
     * shortsLockupViewModel 을 읽는다(NewPipe 는 이 모양을 읽지 못해 직접). 항목 {id, title, sub(조회수 등)}
     */
    private static JSONObject shortsSearch(final String query) throws Exception {
        final JSONObject body = new JSONObject().put("context", webContext(WEB_VERSION, null))
                .put("query", query).put("params", "EgIQCQ==");
        final String res = postJson(YT_API + "search?prettyPrint=false", body);
        final List<JSONObject> found = new ArrayList<>();
        collect(new JSONObject(res), "shortsLockupViewModel", found);
        final JSONArray out = new JSONArray();
        final Set<String> seen = new HashSet<>();
        for (JSONObject x : found) {
            final JSONObject on = x.optJSONObject("onTap");
            final JSONObject cmd = on == null ? null : on.optJSONObject("innertubeCommand");
            final JSONObject ep = cmd == null ? null : cmd.optJSONObject("reelWatchEndpoint");
            final String id = ep == null ? "" : ep.optString("videoId", "");
            if (id.isEmpty() || !seen.add(id)) continue;
            final JSONObject om = x.optJSONObject("overlayMetadata");
            final String title = om == null || om.optJSONObject("primaryText") == null ? "" : om.optJSONObject("primaryText").optString("content", "");
            final String sub = om == null || om.optJSONObject("secondaryText") == null ? "" : om.optJSONObject("secondaryText").optString("content", "");
            out.put(new JSONObject().put("id", id).put("title", title).put("sub", sub));
        }
        return new JSONObject().put("items", out);
    }

    private static void collect(final Object o, final String key, final List<JSONObject> out) {
        if (o instanceof JSONObject) {
            final JSONObject j = (JSONObject) o;
            final JSONObject hit = j.optJSONObject(key);
            if (hit != null) out.add(hit);
            final Iterator<String> it = j.keys();
            while (it.hasNext()) collect(j.opt(it.next()), key, out);
        } else if (o instanceof JSONArray) {
            final JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), key, out);
        }
    }

    /** 검색어 추천(입력하는 동안). 가벼운 요청 하나. 다른 요청처럼 한국어, 한국으로 */
    private static JSONObject suggest(final String query) throws Exception {
        final SuggestionExtractor se = yt().getSuggestionExtractor();
        se.forceLocalization(KO);
        se.forceContentCountry(KR);
        final List<String> l = se.suggestionList(query);
        return new JSONObject().put("items", new JSONArray(l == null ? Collections.emptyList() : l));
    }

    /** 관련 영상만: 보기 쪽을 받아 관련 목록만 읽는다(StreamInfo 를 만들지 않아 영상 주소 풀기를 하지 않음) */
    private static JSONObject related(final String id) throws Exception {
        final StreamExtractor ex = ko(yt().getStreamExtractor(watchUrl(id)));
        ex.fetchPage();
        final InfoItemsCollector<? extends InfoItem, ?> c = ex.getRelatedItems();
        return new JSONObject().put("items", items(c == null ? null : c.getItems()));
    }

    private static JSONObject base(final StreamInfo info) throws Exception {
        return new JSONObject()
                .put("id", info.getId())
                .put("title", info.getName())
                .put("uploader", info.getUploaderName() == null ? "" : info.getUploaderName())
                .put("duration", info.getDuration())
                .put("views", info.getViewCount())
                .put("thumb", bestThumb(info.getThumbnails()));
    }

    /**
     * 이 나라 말 제목(형님 2026-11-13 "한국 영상인데 가끔 제목이 영어로 나온다"). 올린 이가 제목을 여러 말로 달아 두면
     * 유튜브는 보는 사람 말(hl=ko)에 맞는 제목을 보기 화면(next 응답의 videoPrimaryInfoRenderer)에 주는데, NewPipe 의 getName 은
     * 원래 말 제목(player 응답의 videoDetails)을 먼저 쓴다. 그래서 NewPipe 가 이미 받아 둔 next 응답에서 꺼낸다(요청을 더 하지 않음).
     * 못 꺼내면 ""(NewPipe 제목 그대로).
     */
    private static String localTitle(final Object extractor) {
        try {
            final Field f = extractor.getClass().getDeclaredField("nextResponse");
            f.setAccessible(true);
            final String t = primaryTitle(f.get(extractor), 0);   // NewPipe 의 JsonObject 는 Map, JsonArray 는 List 라 그대로 걷는다(복사 안 함)
            return t == null ? "" : t.trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String primaryTitle(final Object o, final int depth) {
        if (depth > 30) return null;
        if (o instanceof Map) {
            final Map<?, ?> j = (Map<?, ?>) o;
            final Object vp = j.get("videoPrimaryInfoRenderer");
            if (vp instanceof Map) {
                final Object t = ((Map<?, ?>) vp).get("title");
                if (t instanceof Map) {
                    final Map<?, ?> tm = (Map<?, ?>) t;
                    if (tm.get("simpleText") instanceof String) return (String) tm.get("simpleText");
                    if (tm.get("runs") instanceof List) {
                        final StringBuilder b = new StringBuilder();
                        for (Object x : (List<?>) tm.get("runs")) {
                            if (x instanceof Map && ((Map<?, ?>) x).get("text") instanceof String) {
                                b.append((String) ((Map<?, ?>) x).get("text"));
                            }
                        }
                        if (b.length() > 0) return b.toString();
                    }
                }
            }
            for (Object v : j.values()) {
                final String t = primaryTitle(v, depth + 1);
                if (t != null) return t;
            }
        } else if (o instanceof List) {
            for (Object v : (List<?>) o) {
                final String t = primaryTitle(v, depth + 1);
                if (t != null) return t;
            }
        }
        return null;
    }

    /**
     * 자세한 정보(형님 2026-11-12 "제목을 누르면 전체 정보, 어디서 몇 만, 날짜까지"). 못 읽은 것은 비워 둔다.
     * date: 올린 날 yyyy-mm-dd(없으면 유튜브 글 그대로), likes, subs: 모르면 -1, desc: 설명 글(줄바꿈 살림), channel: 채널 주소
     */
    private static void details(final StreamInfo info, final JSONObject r) throws Exception {
        String date = "";
        try {
            if (info.getUploadDate() != null) date = info.getUploadDate().offsetDateTime().toLocalDate().toString();
        } catch (Exception ignored) {
        }
        if (date.isEmpty() && info.getTextualUploadDate() != null) date = info.getTextualUploadDate();
        String desc = "";
        try {
            if (info.getDescription() != null && info.getDescription().content() != null) {
                desc = info.getDescription().content();
                if (info.getDescription().type() == Description.Type.HTML) {
                    desc = desc.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "");
                    desc = unescape(desc);
                }
            }
        } catch (Exception ignored) {
        }
        r.put("date", date)
                .put("likes", info.getLikeCount())
                .put("subs", info.getUploaderSubscriberCount())
                .put("desc", desc)
                .put("channel", info.getUploaderUrl() == null ? "" : info.getUploaderUrl())
                .put("avatar", bestThumb(info.getUploaderAvatars()));
    }

    /** &amp; &lt; &#39; &#x27; 같은 것을 글자로(jsoup 은 엔진에서 직접 못 씀) */
    private static String unescape(final String s) {
        final Matcher m = Pattern.compile("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);").matcher(s);
        final StringBuffer b = new StringBuffer();
        while (m.find()) {
            final String e = m.group(1);
            String r = null;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else if (e.equals("amp")) r = "&";
                else if (e.equals("lt")) r = "<";
                else if (e.equals("gt")) r = ">";
                else if (e.equals("quot")) r = "\"";
                else if (e.equals("apos")) r = "'";
                else if (e.equals("nbsp")) r = " ";
            } catch (Exception ignored) {
            }
            m.appendReplacement(b, Matcher.quoteReplacement(r == null ? m.group() : r));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** 올린 때(ms). 모르면 0 */
    private static long uploadMs(final StreamInfoItem s) {
        try {
            return s.getUploadDate() == null ? 0 : s.getUploadDate().offsetDateTime().toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private static JSONArray items(final List<? extends InfoItem> list) throws Exception {
        final JSONArray a = new JSONArray();
        if (list == null) return a;
        for (InfoItem it : list) {
            if (!(it instanceof StreamInfoItem)) continue;
            final StreamInfoItem s = (StreamInfoItem) it;
            String id;
            try {
                id = yt().getStreamLHFactory().getId(s.getUrl());
            } catch (Exception e) {
                continue;
            }
            a.put(new JSONObject()
                    .put("id", id)
                    .put("title", s.getName())
                    .put("uploader", s.getUploaderName() == null ? "" : s.getUploaderName())
                    .put("duration", s.getDuration())
                    .put("views", s.getViewCount())
                    .put("shorts", s.isShortFormContent())
                    .put("when", s.getTextualUploadDate() == null ? "" : s.getTextualUploadDate())
                    .put("time", uploadMs(s))
                    .put("channel", s.getUploaderUrl() == null ? "" : s.getUploaderUrl())
                    .put("thumb", bestThumb(s.getThumbnails())));
        }
        return a;
    }

    /**
     * 댓글(영상, 쇼츠 같음). page 가 없으면 첫 쪽(인기순, 고정 댓글 먼저). 있으면 그 쪽(다음 쪽 또는 답글).
     * 응답 {count, disabled, items:[{id,author,avatar,text,html,when,likes,likesText,replies,repliesPage?,pinned,hearted,owner,edited}], next?}
     */
    private static JSONObject comments(final JSONObject q) throws Exception {
        final String url = watchUrl(q.getString("id"));
        final JSONObject r = new JSONObject();
        final List<CommentsInfoItem> list;
        final Page next;
        final JSONObject pj = q.optJSONObject("page");
        if (pj == null) {
            final CommentsInfo info =
                    CommentsInfo.getInfo(ko(yt().getCommentsExtractor(url)));
            r.put("count", info.getCommentsCount()).put("disabled", info.isCommentsDisabled());
            list = info.getRelatedItems();
            next = info.getNextPage();
        } else {
            final ListExtractor.InfoItemsPage<CommentsInfoItem> pg =
                    ko(yt().getCommentsExtractor(url)).getPage(pageOf(pj));
            list = pg.getItems();
            next = pg.getNextPage();
        }
        final JSONArray a = new JSONArray();
        if (list != null) for (CommentsInfoItem c : list) {
            final Description d = c.getCommentText();
            final JSONObject o = new JSONObject()
                    .put("id", c.getCommentId() == null ? "" : c.getCommentId())
                    .put("author", c.getUploaderName() == null ? "" : c.getUploaderName())
                    .put("avatar", smallThumb(c.getUploaderAvatars()))
                    .put("text", d == null || d.content() == null ? "" : d.content())
                    .put("html", d != null && d.type() == Description.Type.HTML)
                    .put("when", c.getTextualUploadDate() == null ? "" : c.getTextualUploadDate())
                    .put("likes", c.getLikeCount())
                    .put("likesText", c.getTextualLikeCount() == null ? "" : c.getTextualLikeCount())
                    .put("replies", c.getReplyCount())
                    .put("pinned", c.isPinned())
                    .put("hearted", c.isHeartedByUploader())
                    .put("owner", c.isChannelOwner())
                    .put("edited", c.isEdited());
            if (Page.isValid(c.getReplies())) o.put("repliesPage", pageJson(c.getReplies()));
            a.put(o);
        }
        r.put("items", a);
        if (Page.isValid(next)) r.put("next", pageJson(next));
        return r;
    }

    /**
     * 채널(형님 2026-11-12 "올린 이를 누르면 그 채널로"). tab: videos(기본) 또는 shorts.
     * 처음(page 없음): {name, avatar, banner, subs, cid, tabs:[videos,shorts 중 있는 것], items, next?, sorted?:"popular", sortFailed?:true}
     * 다음(page 있음, cid 는 처음 응답의 것): {items, next?}
     */
    private static JSONObject channel(final JSONObject q) throws Exception {
        final String tab = "shorts".equals(q.optString("tab")) ? ChannelTabs.SHORTS
                : ChannelTabs.VIDEOS;
        final JSONObject r = new JSONObject();
        final JSONObject pj = q.optJSONObject("page");
        final ListLinkHandler handler;
        if (pj == null) {
            final ChannelInfo ci =
                    ChannelInfo.getInfo(koFetched(yt().getChannelExtractor(q.getString("url"))));
            r.put("name", ci.getName() == null ? "" : ci.getName())
                    .put("avatar", bestThumb(ci.getAvatars()))
                    .put("banner", bestThumb(ci.getBanners()))
                    .put("subs", ci.getSubscriberCount())
                    .put("cid", ci.getId() == null ? "" : ci.getId());
            ListLinkHandler pick = null;
            final JSONArray tabs = new JSONArray();
            if (ci.getTabs() != null) for (ListLinkHandler t : ci.getTabs()) {
                final List<String> f = t.getContentFilters();
                if (f == null || f.isEmpty()) continue;
                if (f.contains(ChannelTabs.VIDEOS)) tabs.put("videos");
                if (f.contains(ChannelTabs.SHORTS)) tabs.put("shorts");
                if (f.contains(tab)) pick = t;
            }
            r.put("tabs", tabs);
            if (pick == null) return r.put("items", new JSONArray());
            final ChannelTabInfo ti =
                    ChannelTabInfo.getInfo(koFetched(yt().getChannelTabExtractor(pick)));
            // 인기순: 되면 sorted=popular. 해 봤는데 안 되면(요청이나 읽기가 실패, 받은 것이 0개) sortFailed=true 와 최신순.
            // 정렬 칩이 아예 없거나 한 쪽뿐인 작은 채널이면 둘 다 없이 최신순(앱은 sortFailed 일 때만 알린다)
            if ("popular".equals(q.optString("sort")) && Page.isValid(ti.getNextPage())) {
                try {
                    final ListExtractor.InfoItemsPage<InfoItem> pop = popular(pick, ti.getNextPage(), ci.getId(), tab);
                    if (pop != null) {
                        final JSONArray pi = items(pop.getItems());
                        if (pi.length() > 0) {
                            r.put("items", pi).put("sorted", "popular");
                            if (Page.isValid(pop.getNextPage())) r.put("next", pageJson(pop.getNextPage()));
                            return r;
                        }
                        r.put("sortFailed", true);
                    }
                } catch (Exception e) {
                    r.put("sortFailed", true);
                }
            }
            r.put("items", items(ti.getRelatedItems()));
            if (Page.isValid(ti.getNextPage())) r.put("next", pageJson(ti.getNextPage()));
            return r;
        }
        handler = yt().getChannelTabLHFactory().fromQuery(q.getString("cid"), Collections.singletonList(tab), "");
        final ListExtractor.InfoItemsPage<InfoItem> pg =
                ko(yt().getChannelTabExtractor(handler)).getPage(pageOf(pj));
        r.put("items", items(pg.getItems()));
        if (Page.isValid(pg.getNextPage())) r.put("next", pageJson(pg.getNextPage()));
        return r;
    }

    /**
     * 채널 탭의 인기순 첫 쪽(형님 2026-11-12 "영상 인기순, 최신순으로", 쇼츠도). NewPipe 는 정렬을 모른다.
     * 1. 탭 첫 화면(browse)에서 정렬 칩(최신순, 인기순, 날짜순 순서) 중 둘째의 continuation 을 찾는다.
     * 2. NewPipe 가 만든 다음 쪽(Page)의 몸을 그대로 쓰되 continuation 만 그것으로 바꿔 NewPipe 에 넘긴다(목록 풀기는 NewPipe 그대로).
     *    칩을 누른 응답은 reloadContinuationItemsCommand 라 NewPipe 가 못 읽으므로, 이 요청 하나만 HttpDownloader 가
     *    appendContinuationItemsAction 으로 이름을 바꿔 준다. 그 뒤 쪽들은 보통 다음 쪽이라 그대로.
     * 정렬 칩이 없으면 null, 요청이나 읽기가 실패하면 예외(앱은 둘 다 최신순을 받는다).
     */
    private static ListExtractor.InfoItemsPage<InfoItem> popular(
            final ListLinkHandler tabHandler,
            final Page next, final String cid, final String tab) throws Exception {
        final JSONObject body = new JSONObject(new String(next.getBody(), StandardCharsets.UTF_8));
        body.remove("continuation");
        body.put("browseId", cid.startsWith("channel/") ? cid.substring(8) : cid)
                .put("params", ChannelTabs.SHORTS.equals(tab)
                        ? "EgZzaG9ydHPyBgUKA5oBAA==" : "EgZ2aWRlb3PyBgQKAjoA");
        final String token = chipToken(new JSONObject(postJson(next.getUrl(), body)), 1, 0);
        if (token == null) return null;
        final JSONObject b2 = new JSONObject(new String(next.getBody(), StandardCharsets.UTF_8)).put("continuation", token);
        final Page p = new Page(next.getUrl(), next.getId(),
                next.getIds(), next.getCookies(), b2.toString().getBytes(StandardCharsets.UTF_8));
        HttpDownloader.RELOAD_AS_APPEND.set(Boolean.TRUE);
        try {
            return ko(yt().getChannelTabExtractor(tabHandler)).getPage(p);
        } finally {
            HttpDownloader.RELOAD_AS_APPEND.remove();
        }
    }

    /** 정렬 칩 줄(chipBarViewModel 또는 옛 feedFilterChipBarRenderer)의 n 번째 칩의 continuation */
    private static String chipToken(final Object o, final int n, final int depth) {
        if (depth > 40) return null;
        if (o instanceof JSONObject) {
            final JSONObject j = (JSONObject) o;
            final JSONObject bar = j.optJSONObject("chipBarViewModel");
            if (bar != null) {
                final JSONArray chips = bar.optJSONArray("chips");
                if (chips != null && chips.length() > n) {
                    final String t = tokenIn(chips.optJSONObject(n), 0);
                    if (t != null) return t;
                }
            }
            final JSONObject old = j.optJSONObject("feedFilterChipBarRenderer");
            if (old != null) {
                final JSONArray chips = old.optJSONArray("contents");
                if (chips != null && chips.length() > n) {
                    final String t = tokenIn(chips.optJSONObject(n), 0);
                    if (t != null) return t;
                }
            }
            for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                final String t = chipToken(j.opt(it.next()), n, depth + 1);
                if (t != null) return t;
            }
        } else if (o instanceof JSONArray) {
            final JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                final String t = chipToken(a.opt(i), n, depth + 1);
                if (t != null) return t;
            }
        }
        return null;
    }

    /** 칩 하나 안의 continuationCommand.token(어디에 있든 처음 것) */
    private static String tokenIn(final Object o, final int depth) {
        if (o == null || depth > 12) return null;
        if (o instanceof JSONObject) {
            final JSONObject j = (JSONObject) o;
            final JSONObject cc = j.optJSONObject("continuationCommand");
            if (cc != null && cc.optString("token", "").length() > 0) return cc.optString("token");
            for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                final String t = tokenIn(j.opt(it.next()), depth + 1);
                if (t != null) return t;
            }
        } else if (o instanceof JSONArray) {
            final JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                final String t = tokenIn(a.opt(i), depth + 1);
                if (t != null) return t;
            }
        }
        return null;
    }

    /**
     * 구독(앱 안, 형님 2026-11-12 "자체적으로 구독, 최신 나오면 보여 주고"). {"m":"feed","channels":[채널 주소...]}
     * 채널마다 유튜브 RSS(가벼움, 최근 15개쯤)를 넷씩 함께 받아 하나로. 응답 {items:[..., time(올린 때 ms)], failed:n}
     */
    private static JSONObject feed(final JSONObject q) throws Exception {
        final JSONArray ch = q.optJSONArray("channels");
        final JSONArray out = new JSONArray();
        if (ch == null || ch.length() == 0) return new JSONObject().put("items", out).put("failed", 0);
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        final List<Future<JSONArray>> fs = new ArrayList<>();
        for (int i = 0; i < ch.length() && i < 300; i++) {
            final String url = ch.optString(i);
            fs.add(pool.submit(() -> channelNew(url)));
        }
        int failed = 0;
        for (Future<JSONArray> f : fs) {
            try {
                final JSONArray a = f.get(30, TimeUnit.SECONDS);
                for (int i = 0; i < a.length(); i++) out.put(a.get(i));
            } catch (Exception e) {
                failed++;
            }
        }
        pool.shutdownNow();
        return new JSONObject().put("items", out).put("failed", failed);
    }

    /**
     * 한 채널의 새 영상. 먼저 유튜브 RSS(가벼움), RSS 가 안 되면(유튜브 RSS 가 가끔 404, 형님 2026-11-13 "구독 새 영상이 계속 돌기만")
     * 채널의 영상 탭 첫 쪽(최신순, 한국어로)에서 15개.
     */
    private static JSONArray channelNew(final String url) throws Exception {
        try {
            final JSONArray a = items(FeedInfo.getInfo(yt(), url).getRelatedItems());
            if (a.length() > 0) return a;
        } catch (Exception ignored) {
        }
        final ChannelInfo ci =
                ChannelInfo.getInfo(koFetched(yt().getChannelExtractor(url)));
        if (ci.getTabs() != null) for (ListLinkHandler t : ci.getTabs()) {
            final List<String> f = t.getContentFilters();
            if (f == null || !f.contains(ChannelTabs.VIDEOS)) continue;
            final JSONArray all = items(ChannelTabInfo.getInfo(koFetched(yt().getChannelTabExtractor(t))).getRelatedItems());
            final JSONArray out = new JSONArray();
            for (int i = 0; i < all.length() && i < 15; i++) {
                final JSONObject o = all.getJSONObject(i);
                if (o.optString("channel").isEmpty()) o.put("channel", url);   // 앱이 채널별로 거를 수 있게
                out.put(o);
            }
            return out;
        }
        return new JSONArray();
    }

    /**
     * 노래 라디오(형님 2026-11-17 "노래로 틀면 관련 영상 말고 유튜브 뮤직, YMusic 처럼 노래들 목록이 알아서 들어가게").
     * 유튜브 뮤직의 곡 라디오 믹스(list=RDAMVM + 영상 ID)를 NewPipe 의 믹스 목록으로 읽는다(처음 25곡쯤, 다음 쪽으로 더).
     * 응답 {items:[...], next?}. 첫 곡은 이 곡 자신.
     */
    private static JSONObject radio(final JSONObject q) throws Exception {
        final String id = q.getString("id");
        final String url = "https://www.youtube.com/watch?v=" + id + "&list=RDAMVM" + id;
        final JSONObject pj = q.optJSONObject("page");
        final PlaylistExtractor ex = ko(yt().getPlaylistExtractor(url));
        final JSONObject r = new JSONObject();
        if (pj == null) {
            ex.fetchPage();
            final PlaylistInfo pi = PlaylistInfo.getInfo(ex);
            r.put("items", items(pi.getRelatedItems()));
            if (Page.isValid(pi.getNextPage())) r.put("next", pageJson(pi.getNextPage()));
        } else {
            final ListExtractor.InfoItemsPage<StreamInfoItem> pg = ex.getPage(pageOf(pj));
            r.put("items", items(pg.getItems()));
            if (Page.isValid(pg.getNextPage())) r.put("next", pageJson(pg.getNextPage()));
        }
        return r;
    }

    /** NewPipe 의 Page 를 JSON 으로(앱은 안을 모르고 그대로 돌려준다) */
    private static JSONObject pageJson(final Page p) throws Exception {
        final JSONObject o = new JSONObject();
        if (p.getUrl() != null) o.put("url", p.getUrl());
        if (p.getId() != null) o.put("pid", p.getId());
        if (p.getIds() != null) o.put("ids", new JSONArray(p.getIds()));
        if (p.getCookies() != null) o.put("cookies", new JSONObject(p.getCookies()));
        if (p.getBody() != null) o.put("body", Base64.getEncoder().encodeToString(p.getBody()));
        return o;
    }

    private static Page pageOf(final JSONObject o) throws Exception {
        List<String> ids = null;
        final JSONArray ia = o.optJSONArray("ids");
        if (ia != null) {
            ids = new ArrayList<>();
            for (int i = 0; i < ia.length(); i++) ids.add(ia.getString(i));
        }
        Map<String, String> cookies = null;
        final JSONObject co = o.optJSONObject("cookies");
        if (co != null) {
            cookies = new HashMap<>();
            for (Iterator<String> it = co.keys(); it.hasNext(); ) {
                final String k = it.next();
                cookies.put(k, co.getString(k));
            }
        }
        final byte[] body = o.has("body") ? Base64.getDecoder().decode(o.getString("body")) : null;
        return new Page(o.optString("url", null), o.optString("pid", null), ids, cookies, body);
    }

    /** 작은 그림(댓글 얼굴): 높이 48 이상 가운데 가장 작은 것 */
    private static String smallThumb(final List<Image> imgs) {
        if (imgs == null || imgs.isEmpty()) return "";
        Image pick = null;
        for (Image i : imgs) {
            if (i.getHeight() >= 48 && (pick == null || i.getHeight() < pick.getHeight())) pick = i;
        }
        if (pick == null) pick = imgs.get(imgs.size() - 1);
        return pick.getUrl();
    }

    /**
     * 많이 본 구간(유튜브의 "가장 많이 다시 본" 산 모양, 형님 2026-11-12 "많이 본 시점이 산처럼 올라가는 것").
     * NewPipe 는 이것을 읽지 않아 직접: youtubei/v1/next 의 macroMarkersListEntity(MARKER_TYPE_HEATMAP) 의 markers.
     * 응답 {items:[[시작ms, 길이ms, 세기0~1], ...]} (없으면 빈 배열). 조회수가 적은 영상은 원래 없다.
     */
    private static JSONObject heatmap(final String id) throws Exception {
        final JSONObject body = new JSONObject().put("context", webContext(WEB_VERSION, null)).put("videoId", id);
        final String res = postJson(YT_API + "next?prettyPrint=false", body);
        final JSONArray out = new JSONArray();
        findHeat(new JSONObject(res), out, 0);
        return new JSONObject().put("items", out);
    }

    /** 응답 어디에 있든 "세기" 가 든 markers 배열을 찾는다(처음 찾은 것 하나) */
    private static void findHeat(final Object o, final JSONArray out, final int depth) throws Exception {
        if (out.length() > 0 || depth > 40) return;
        if (o instanceof JSONObject) {
            final JSONObject j = (JSONObject) o;
            final JSONArray m = j.optJSONArray("markers");
            if (m != null && m.length() > 0 && m.optJSONObject(0) != null && m.optJSONObject(0).has("intensityScoreNormalized")) {
                for (int i = 0; i < m.length(); i++) {
                    final JSONObject k = m.optJSONObject(i);
                    if (k == null) continue;
                    out.put(new JSONArray().put(k.optLong("startMillis")).put(k.optLong("durationMillis"))
                            .put(k.optDouble("intensityScoreNormalized", 0)));
                }
                return;
            }
            for (Iterator<String> it = j.keys(); it.hasNext(); ) findHeat(j.opt(it.next()), out, depth + 1);
        } else if (o instanceof JSONArray) {
            final JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) findHeat(a.opt(i), out, depth + 1);
        }
    }

    private static String bestThumb(final List<Image> imgs) {
        if (imgs == null || imgs.isEmpty()) return "";
        Image best = imgs.get(0);
        for (Image i : imgs) if (i.getHeight() > best.getHeight()) best = i;
        return best.getUrl();
    }
}
