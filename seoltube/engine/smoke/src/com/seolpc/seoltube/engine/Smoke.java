package com.seolpc.seoltube.engine;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 올리기 전 시험(자동 빌드가 부른다): 엔진 소스를 같은 NewPipe 판과 함께 JVM 에서 돌려 검색과 영상 정보가 되는지 본다.
 * 되면 0, 안 되면 1 로 끝난다. 유튜브가 빌드 서버(데이터 센터 주소)를 막아서 생긴 실패(봇 확인, 429)는 엔진 탓이 아니므로
 * 경고만 하고 넘어간다. 그 밖의 실패(읽기 실패, 클래스 없음 등)는 막는다.
 */
public final class Smoke {
    private static boolean blocked(final String err) {
        // 유튜브가 빌드 서버 주소를 봇으로 보고 막은 것(영어, 한국어 문구, LOGIN_REQUIRED, 429)
        return err.contains("SignInConfirmNotBot") || err.contains("ReCaptcha") || err.contains("not a bot")
                || err.contains("LOGIN_REQUIRED") || err.contains("봇이 아님") || err.contains("Sign in to confirm") || err.contains("429");
    }

    private static JSONObject call(final Engine e, final JSONObject q) throws Exception {
        JSONObject r = new JSONObject(e.call(q.toString()));
        if (!r.optBoolean("ok")) {
            Thread.sleep(3000);
            r = new JSONObject(e.call(q.toString()));   // 한 번 더(잠깐 실패)
        }
        System.out.println(q.optString("m") + ": " + (r.optBoolean("ok") ? "ok" : r.optString("error")));
        return r;
    }

    public static void main(final String[] args) throws Exception {
        int bad = 0;
        final Engine e = new Engine();

        final JSONObject v = call(e, new JSONObject().put("m", "version"));
        if (!v.optBoolean("ok") || v.optInt("api", -1) != Engine.API) {
            System.out.println("version 응답이 이상합니다: " + v);
            bad++;
        }

        final JSONObject s = call(e, new JSONObject().put("m", "search").put("q", "아이유"));
        final JSONArray items = s.optJSONArray("items");
        String id = "";
        if (!s.optBoolean("ok")) {
            if (blocked(s.optString("error"))) System.out.println("::warning::검색이 막혔습니다(빌드 서버 주소): " + s.optString("error"));
            else bad++;
        } else if (items == null || items.length() < 5) {
            System.out.println("검색 결과가 너무 적습니다: " + (items == null ? 0 : items.length()));
            bad++;
        } else {
            final JSONObject first = items.getJSONObject(0);
            id = first.optString("id");
            System.out.println("  첫 결과 " + id + " " + first.optString("title") + " 조회수 " + first.optLong("views"));
            if (id.length() != 11 || first.optString("title").isEmpty()) bad++;
        }

        if (!id.isEmpty()) {
            final JSONObject st = call(e, new JSONObject().put("m", "streams").put("id", id));
            if (!st.optBoolean("ok")) {
                if (blocked(st.optString("error"))) System.out.println("::warning::영상 정보가 막혔습니다(빌드 서버 주소): " + st.optString("error"));
                else bad++;
            } else {
                final int vo = st.optJSONArray("videoOnly") == null ? 0 : st.getJSONArray("videoOnly").length();
                final int au = st.optJSONArray("audio") == null ? 0 : st.getJSONArray("audio").length();
                System.out.println("  영상 " + vo + "개, 소리 " + au + "개, 제목 " + st.optString("title"));
                if (au == 0 || st.optString("title").isEmpty()) bad++;
            }
        }

        System.out.println(bad == 0 ? "시험 통과" : "시험 실패 " + bad);
        System.exit(bad == 0 ? 0 : 1);
    }
}
