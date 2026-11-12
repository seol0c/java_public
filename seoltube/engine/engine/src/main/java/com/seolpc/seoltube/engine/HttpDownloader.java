package com.seolpc.seoltube.engine;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** NewPipe 가 인터넷에 묻는 통로. 안드로이드 기본 HttpURLConnection 만 쓴다. */
final class HttpDownloader extends Downloader {
    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    /**
     * 유튜브 재생 스크립트(…/s/player/<판>/…/base.js, 약 2MB) 캐시 폴더. 앱이 init 으로 알려 준다.
     * 영상 주소를 풀려면 이 스크립트가 필요한데, 앱을 켤 때마다 다시 받으면 첫 재생이 느리다
     * (형님 2026-11-11 "그냥 캐시로만, 누르면 그거에 집중"). 주소에 판 번호가 있어 판이 바뀌면 새로 받는다. 최신 하나만 남긴다.
     */
    static volatile java.io.File jsCacheDir;

    /** 이 스레드의 다음 응답에서 reloadContinuationItemsCommand 를 appendContinuationItemsAction 으로(채널 인기순 첫 쪽, Engine.popular) */
    static final ThreadLocal<Boolean> RELOAD_AS_APPEND = new ThreadLocal<>();

    private static java.io.File jsFile(final String url) {
        final java.io.File d = jsCacheDir;
        if (d == null || !url.contains("/s/player/") || !url.endsWith("base.js")) return null;
        return new java.io.File(d, Integer.toHexString(url.hashCode()) + ".js");
    }

    @Override
    public Response execute(final Request request) throws IOException, ReCaptchaException {
        final String url = request.url();
        final java.io.File jf = "GET".equals(request.httpMethod()) ? jsFile(url) : null;
        if (jf != null && jf.isFile() && jf.length() > 0) {
            try (java.io.InputStream in = new java.io.FileInputStream(jf)) {
                return new Response(200, "OK (cache)", null, readAll(in), url);
            }
        }
        final Response r = executeNet(request);
        if (jf != null && r.responseCode() == 200 && r.responseBody().length() > 1000) {
            try {
                jf.getParentFile().mkdirs();
                final java.io.File tmp = new java.io.File(jf.getPath() + ".tmp");
                try (java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                    out.write(r.responseBody().getBytes(StandardCharsets.UTF_8));
                }
                tmp.renameTo(jf);
                // 최신 하나만 남김(방금 받은 것 말고는 지움)
                final java.io.File[] all = jf.getParentFile().listFiles((x, n) -> n.endsWith(".js"));
                if (all != null) for (java.io.File f : all) if (!f.getName().equals(jf.getName())) f.delete();
            } catch (Exception ignored) {
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
            final org.json.JSONObject j = new org.json.JSONObject(body);
            final org.json.JSONArray acts = j.optJSONArray("onResponseReceivedActions");
            if (acts == null) return body;
            final org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < acts.length(); i++) {
                final org.json.JSONObject a = acts.optJSONObject(i);
                if (a == null) continue;
                final org.json.JSONObject rc = a.optJSONObject("reloadContinuationItemsCommand");
                if (rc == null) {
                    out.put(a);
                    continue;
                }
                final org.json.JSONArray items = rc.optJSONArray("continuationItems");
                if (items == null) continue;
                final String s = items.toString();
                if (!s.contains("richItemRenderer") && !s.contains("continuationItemRenderer")) continue;   // 칩 줄
                out.put(new org.json.JSONObject().put("appendContinuationItemsAction",
                        new org.json.JSONObject().put("continuationItems", items)));
            }
            j.put("onResponseReceivedActions", out);
            return j.toString();
        } catch (Exception e) {
            return body;
        }
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
