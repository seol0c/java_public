package com.seolpc.seoltube.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** NewPipe 가 인터넷에 묻는 통로. 안드로이드 기본 HttpURLConnection 만 쓴다. */
final class HttpDownloader extends Downloader {
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    /**
     * 유튜브 재생 스크립트(…/s/player/<판>/…/base.js, 약 2MB) 캐시 폴더. 앱이 init 으로 알려 준다.
     * 영상 주소를 풀려면 이 스크립트가 필요한데, 앱을 켤 때마다 다시 받으면 첫 재생이 느리다
     * (형님 2026-11-11 "그냥 캐시로만, 누르면 그거에 집중"). 주소에 판 번호가 있어 판이 바뀌면 새로 받는다. 최신 하나만 남긴다.
     */
    static volatile File jsCacheDir;

    /** 이 스레드의 다음 응답에서 reloadContinuationItemsCommand 를 appendContinuationItemsAction 으로(채널 인기순 첫 쪽, Engine.popular) */
    static final ThreadLocal<Boolean> RELOAD_AS_APPEND = new ThreadLocal<>();

    private static File jsFile(final String url) {
        final File d = jsCacheDir;
        if (d == null || !url.contains("/s/player/") || !url.endsWith("base.js")) return null;
        return new File(d, Integer.toHexString(url.hashCode()) + ".js");
    }

    @Override
    public Response execute(final Request request) throws IOException, ReCaptchaException {
        final String url = request.url();
        final File jf = "GET".equals(request.httpMethod()) ? jsFile(url) : null;
        if (jf != null && jf.isFile() && jf.length() > 0) {
            try (InputStream in = new FileInputStream(jf)) {
                return new Response(200, "OK (cache)", null, readAll(in), url);
            }
        }
        final Response r = executeNet(request);
        if (jf != null && r.responseCode() == 200 && r.responseBody().length() > 1000) {
            File tmp = null;
            try {
                jf.getParentFile().mkdirs();
                // 받는 것마다 다른 임시 파일에 다 쓰고 이름만 바꾼다(동시에 둘이 받아도 서로 덮거나 반쯤 쓴 것을 읽지 않게)
                tmp = File.createTempFile(jf.getName() + "-", ".tmp", jf.getParentFile());
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(r.responseBody().getBytes(StandardCharsets.UTF_8));
                }
                if (tmp.renameTo(jf)) tmp = null;
                // 최신 하나만 남김(방금 받은 것 말고는 지움). 남은 임시 파일도 1분 지난 것은 지움(지금 쓰는 중인 것은 두고)
                final long old = System.currentTimeMillis() - 60_000L;
                final File[] all = jf.getParentFile().listFiles((x, n) -> n.endsWith(".js") || n.endsWith(".tmp"));
                if (all != null) for (File f : all) {
                    if (f.getName().equals(jf.getName())) continue;
                    if (f.getName().endsWith(".tmp") && f.lastModified() > old) continue;
                    f.delete();
                }
            } catch (Exception ignored) {
            } finally {
                if (tmp != null) tmp.delete();
            }
        }
        return r;
    }

    private Response executeNet(final Request request) throws IOException, ReCaptchaException {
        final String url = request.url();
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod(request.httpMethod());
            c.setInstanceFollowRedirects(true);
            boolean ua = false;
            for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
                if (h.getKey() == null) continue;
                if (h.getKey().equalsIgnoreCase("User-Agent")) ua = true;
                boolean first = true;
                for (String v : h.getValue()) {
                    if (first) c.setRequestProperty(h.getKey(), v);
                    else c.addRequestProperty(h.getKey(), v);
                    first = false;
                }
            }
            if (!ua) c.setRequestProperty("User-Agent", USER_AGENT);

            final byte[] data = request.dataToSend();
            if (data != null) {
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(data);
                }
            }

            final int code = c.getResponseCode();
            if (code == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }
            String body = "";
            if (!"HEAD".equals(request.httpMethod())) {
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) body = readAll(in);
            }
            if (Boolean.TRUE.equals(RELOAD_AS_APPEND.get()) && body.contains("reloadContinuationItemsCommand")) body = reloadAsAppend(body);
            if (url.contains("youtube.com") && (body.contains("만") || body.contains("천") || body.contains("억"))) body = fullCounts(body);
            return new Response(code, c.getResponseMessage(), c.getHeaderFields(), body,
                    c.getURL().toString());
        } finally {
            c.disconnect();
        }
    }

    /**
     * 칩을 누른 응답(reloadContinuationItemsCommand 가 둘: 칩 줄, 목록)에서 목록 쪽만 appendContinuationItemsAction 으로.
     * NewPipe 는 첫 append 만 읽으므로 칩 줄은 뺀다. 못 고치면 그대로.
     */
    private static String reloadAsAppend(final String body) {
        try {
            final JSONObject j = new JSONObject(body);
            final JSONArray acts = j.optJSONArray("onResponseReceivedActions");
            if (acts == null) return body;
            final JSONArray out = new JSONArray();
            for (int i = 0; i < acts.length(); i++) {
                final JSONObject a = acts.optJSONObject(i);
                if (a == null) continue;
                final JSONObject rc = a.optJSONObject("reloadContinuationItemsCommand");
                if (rc == null) {
                    out.put(a);
                    continue;
                }
                final JSONArray items = rc.optJSONArray("continuationItems");
                if (items == null) continue;
                final String s = items.toString();
                if (!s.contains("richItemRenderer") && !s.contains("continuationItemRenderer")) continue;   // 칩 줄
                out.put(new JSONObject().put("appendContinuationItemsAction",
                        new JSONObject().put("continuationItems", items)));
            }
            j.put("onResponseReceivedActions", out);
            return j.toString();
        } catch (Exception e) {
            return body;
        }
    }

    /**
     * 조회수 7.4만회, 구독자 1040만명, 좋아요 1.2천개 같은 줄인 한국어 수를 74000회처럼 푼다(NewPipe 가 한국어로 물을 때 바로 읽게).
     * 응답 전체가 아니라 NewPipe 가 수를 읽는 칸 안에서만 푼다(제목, 설명, 댓글 글의 "구독자 1만명" 같은 것은 그대로).
     * COUNT_KEYS: 수만 담는 칸(안의 글을 모두 푼다). MIXED_KEYS: 수와 다른 글이 섞인 칸(올린 이 이름 등, 글 값 전체가 수일 때만 푼다).
     */
    private static final Pattern KEY = Pattern.compile(
            "\"(viewCountText|shortViewCountText|viewCount|shortViewCount|subscriberCountText|videoCountText|voteCount"
                    + "|likeCountNotliked|likeCountLiked|replyCount"
                    + "|metadataParts|secondaryText|videoInfo|segmentedLikeDislikeButtonViewModel|segmentedLikeDislikeButtonRenderer|likeButtonViewModel)\":");
    private static final Set<String> MIXED_KEYS = new HashSet<>(Arrays.asList(
            "metadataParts", "secondaryText", "videoInfo", "segmentedLikeDislikeButtonViewModel", "segmentedLikeDislikeButtonRenderer", "likeButtonViewModel"));

    /** 글 안 어디서나: 조회수 7.4만회 */
    private static final Pattern KO_COUNT =
            Pattern.compile("(조회수\\s?|구독자\\s?|좋아요\\s?)(\\d+(?:\\.\\d+)?)(천|만|억)(회|명|개)");

    /** JSON 글 값 전체가 수(와 단위)일 때만: "840만", "4.6천", "조회수 840만회" */
    private static final Pattern KO_WHOLE =
            Pattern.compile("\"((?:조회수|구독자|좋아요)\\s?)?(\\d+(?:\\.\\d+)?)(천|만|억)(회|명|개)?\"");

    static String fullCounts(final String body) {
        final Matcher m = KEY.matcher(body);
        StringBuilder b = null;
        int last = 0, from = 0;
        while (from < body.length() && m.find(from)) {
            final int vs = m.end();
            final int ve = valueEnd(body, vs);
            if (ve <= vs) {
                from = vs;
                continue;
            }
            final String seg = body.substring(vs, ve);
            final String rew = MIXED_KEYS.contains(m.group(1)) ? expand(seg, KO_WHOLE, true)
                    : expand(expand(seg, KO_COUNT, false), KO_WHOLE, true);
            if (!rew.equals(seg)) {
                if (b == null) b = new StringBuilder(body.length() + 64);
                b.append(body, last, vs).append(rew);
                last = ve;
            }
            from = ve;
        }
        if (b == null) return body;
        return b.append(body, last, body.length()).toString();
    }

    /** body[i] 에서 시작하는 JSON 값 하나(글, 객체, 배열, 수)의 끝 자리(다음 글자). 못 찾으면 -1 */
    private static int valueEnd(final String s, int i) {
        final int n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= n) return -1;
        final char c0 = s.charAt(i);
        if (c0 != '"' && c0 != '{' && c0 != '[') {
            while (i < n && ",}]".indexOf(s.charAt(i)) < 0) i++;
            return i;
        }
        int depth = 0;
        boolean str = false;
        for (; i < n; i++) {
            final char c = s.charAt(i);
            if (str) {
                if (c == '\\') i++;
                else if (c == '"') {
                    str = false;
                    if (depth == 0) return i + 1;   // 글 값 하나
                }
            } else if (c == '"') {
                str = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (--depth == 0) return i + 1;
            }
        }
        return -1;
    }

    /** 무리 1 앞말, 2 수, 3 단위, 4 뒷말. quoted 면 앞뒤 따옴표째 */
    private static String expand(final String body, final Pattern p, final boolean quoted) {
        final Matcher m = p.matcher(body);
        if (!m.find()) return body;
        m.reset();
        final StringBuffer b = new StringBuffer();
        while (m.find()) {
            final double v = Double.parseDouble(m.group(2));
            final String unit = m.group(3);
            final long mul = "천".equals(unit) ? 1000L : "만".equals(unit) ? 10000L : 100000000L;
            final String num = Long.toString(Math.round(v * mul));   // 쉼표 없이(NewPipe 가 한국어일 때 74,000 을 740 으로 읽음)
            final String pre = m.group(1) == null ? "" : m.group(1);
            final String post = m.group(4) == null ? "" : m.group(4);
            final String out = quoted ? "\"" + pre + num + post + "\"" : pre + num + post;
            m.appendReplacement(b, Matcher.quoteReplacement(out));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static String readAll(final InputStream in) throws IOException {
        try (InputStream is = in) {
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
