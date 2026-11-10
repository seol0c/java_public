package com.seolpc.seoltube.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.Collections;
import java.util.List;

/**
 * seoltube 엔진의 문 하나.
 * 앱은 이 클래스를 이름으로 불러 call(요청 JSON) 만 부른다. 앱과 엔진 사이에 같은 클래스를 나눠 갖지 않으므로
 * NewPipe 가 안쪽을 바꿔도 앱은 다시 깔 필요가 없다.
 *
 * 요청 {"m":"version"} {"m":"audio","id":"영상ID"} {"m":"streams","id":..} {"m":"search","q":..} {"m":"related","id":..} {"m":"suggest","q":..} {"m":"music","q":..,"part":0|1|2}
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
                case "audio": r = audio(q.getString("id")); break;
                case "streams": r = streams(q.getString("id")); break;
                case "search": r = search(q.getString("q")); break;
                case "related": r = related(q.getString("id")); break;
                case "suggest": r = suggest(q.getString("q")); break;
                case "music": r = music(q.getString("q"), q.optInt("part", 0)); break;
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

    private static StreamingService yt() {
        return ServiceList.YouTube;
    }

    private static String watchUrl(final String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }

    private static JSONObject version() throws Exception {
        return new JSONObject().put("api", API).put("newpipe", BuildConfig.NEWPIPE);
    }

    /** 받기용: 한 번에 받을 수 있는(PROGRESSIVE) 소리 가운데 원래 언어, 가장 높은 음질 하나 */
    private static JSONObject audio(final String id) throws Exception {
        final StreamInfo info = StreamInfo.getInfo(yt(), watchUrl(id));
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
        final JSONObject s = new JSONObject()
                .put("url", best.getContent())
                .put("mime", best.getFormat() == null ? "" : best.getFormat().getMimeType())
                .put("ext", best.getFormat() == null ? "" : best.getFormat().getSuffix())
                .put("kbps", best.getAverageBitrate())
                .put("codec", best.getCodec() == null ? "" : best.getCodec())
                .put("size", best.getItagItem() == null ? -1 : best.getItagItem().getContentLength());
        return r.put("audio", s);
    }

    /** 영상 보기용: 영상만(높은 화질), 소리만, 둘 다 든 것(낮은 화질) */
    private static JSONObject streams(final String id) throws Exception {
        final StreamInfo info = StreamInfo.getInfo(yt(), watchUrl(id));
        final JSONObject r = base(info);
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
        final SearchInfo info = SearchInfo.getInfo(yt(),
                yt().getSearchQHFactory().fromQuery(query, Collections.singletonList("videos"), ""));
        return new JSONObject().put("items", items(info.getRelatedItems()));
    }

    /**
     * 음악 검색: 일반 유튜브 검색에서 공식 음원만 고른다(isOfficialAudio).
     * 한국은 로그인 없이 유튜브 뮤직의 '노래' 검색이 막혀 있어서 이렇게 한다(2026-11-10 확인).
     * 두 검색을 동시에: ① 검색어 그대로(영상만, 인기순에 가까움) ② 검색어 + " auto-generated"(음원이 많이 나옴).
     * ①을 앞에, ②를 뒤에, 같은 영상은 한 번만.
     */
    /**
     * part: 0 = 둘 다(합쳐서), 1 = 검색어 그대로만, 2 = 검색어 + " auto-generated" 만.
     * 앱은 1 과 2 를 따로 동시에 불러 먼저 온 것부터 보여 준다(형님 2026-11-10 "음악 검색이 검색 중에서 너무 오래 걸린다").
     */
    private static JSONObject music(final String query, final int part) throws Exception {
        if (part == 1 || part == 2) {
            final java.util.LinkedHashMap<String, JSONObject> out = new java.util.LinkedHashMap<>();
            addSongs(videoSearch(part == 1 ? query : query + " auto-generated"), out);
            return new JSONObject().put("items", new JSONArray(out.values()));
        }
        return music(query);
    }

    private static JSONObject music(final String query) throws Exception {
        final Object[] second = new Object[1];
        final Thread t = new Thread(() -> {
            try {
                second[0] = videoSearch(query + " auto-generated");
            } catch (Throwable e) {
                second[0] = e;
            }
        }, "music-search-2");
        t.start();
        List<InfoItem> first = null;
        Exception firstErr = null;
        try {
            first = videoSearch(query);
        } catch (Exception e) {
            firstErr = e;
        }
        t.join(20000);
        final java.util.LinkedHashMap<String, JSONObject> out = new java.util.LinkedHashMap<>();
        if (first != null) addSongs(first, out);
        if (second[0] instanceof List) {
            @SuppressWarnings("unchecked") final List<InfoItem> l = (List<InfoItem>) second[0];
            addSongs(l, out);
        }
        if (out.isEmpty() && firstErr != null) throw firstErr;
        return new JSONObject().put("items", new JSONArray(out.values()));
    }

    /**
     * 음악 탭에 넣을 것(형님 2026-11-10 "딱 봐도 공식 음원, 영상 없는 앨범 재킷만 있는 음원, 또는 official audio 같은 것들만"):
     * ① 음반사가 올린 공식 음원(설명이 "Provided to YouTube by" 로 시작, 앨범 재킷만 나오는 것)
     * ② 제목에 Official Audio, (Audio), [Audio] 가 붙은 것
     */
    private static boolean isOfficialAudio(final StreamInfoItem s) throws Exception {
        final String d = s.getShortDescription();
        if (d != null && d.startsWith("Provided to YouTube by")) return true;
        final String t = s.getName() == null ? "" : s.getName().toLowerCase(java.util.Locale.ROOT);
        return t.contains("official audio") || t.contains("(audio)") || t.contains("[audio]");
    }

    private static List<InfoItem> videoSearch(final String q) throws Exception {
        return SearchInfo.getInfo(yt(),
                yt().getSearchQHFactory().fromQuery(q, Collections.singletonList("videos"), "")).getRelatedItems();
    }

    private static void addSongs(final List<InfoItem> list, final java.util.Map<String, JSONObject> out) throws Exception {
        for (InfoItem it : list) {
            if (!(it instanceof StreamInfoItem)) continue;
            final StreamInfoItem s = (StreamInfoItem) it;
            if (!isOfficialAudio(s)) continue;
            final String id;
            try {
                id = yt().getStreamLHFactory().getId(s.getUrl());
            } catch (Exception e) {
                continue;
            }
            if (out.containsKey(id)) continue;
            String up = s.getUploaderName() == null ? "" : s.getUploaderName();
            if (up.endsWith(" - Topic")) up = up.substring(0, up.length() - 8);
            out.put(id, new JSONObject()
                    .put("id", id)
                    .put("title", s.getName())
                    .put("uploader", up)
                    .put("duration", s.getDuration())
                    .put("views", s.getViewCount())
                    .put("thumb", bestThumb(s.getThumbnails())));
        }
    }

    /** 검색어 추천(입력하는 동안). 가벼운 요청 하나 */
    private static JSONObject suggest(final String query) throws Exception {
        final List<String> l = yt().getSuggestionExtractor().suggestionList(query);
        return new JSONObject().put("items", new JSONArray(l == null ? Collections.emptyList() : l));
    }

    private static JSONObject related(final String id) throws Exception {
        final StreamInfo info = StreamInfo.getInfo(yt(), watchUrl(id));
        return new JSONObject().put("items", items(info.getRelatedItems()));
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
                    .put("thumb", bestThumb(s.getThumbnails())));
        }
        return a;
    }

    private static String bestThumb(final List<Image> imgs) {
        if (imgs == null || imgs.isEmpty()) return "";
        Image best = imgs.get(0);
        for (Image i : imgs) if (i.getHeight() > best.getHeight()) best = i;
        return best.getUrl();
    }
}
