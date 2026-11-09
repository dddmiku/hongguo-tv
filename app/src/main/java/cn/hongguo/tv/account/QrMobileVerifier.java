package cn.hongguo.tv.account;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads a tentative session without saving or mutating any account data. */
final class QrMobileVerifier {
    interface Reader { JSONObject read(String path, Map<String,String> query, Account candidate) throws IOException; }

    static Account verify(Account candidate, Reader reader) throws IOException {
        try {
            JSONObject info = reader.read("/reading/user/info/v", null, candidate);
            JSONObject user = info == null ? null : info.optJSONObject("data");
            String uid = user == null ? "" : user.optString("user_id", user.optString("user_id_str", user.optString("uid", "")));
            if (!ok(info) || user == null || "0".equals(uid) || "-1".equals(uid)
                    || (user.has("is_login") && !user.optBoolean("is_login", true))
                    || !uid.matches("[A-Za-z0-9#_+./=-]{1,512}")) {
                throw unavailable("扫码账号暂时无法连接手机账号数据，请使用验证码登录");
            }
            String name = user.optString("user_name", user.optString("name", ""));
            if (name.length() > 200) name = name.substring(0,200);
            Account verified = Account.candidate(candidate.cookie, candidate.token, uid, name);
            Map<String,String> query = new LinkedHashMap<>();
            query.put("book_type","2"); query.put("offset","0"); query.put("limit","1");
            query.put("query_soft_deleted","false"); query.put("is_first_load","false");
            query.put("last_min_read_timestamp_ms","0"); query.put("full_field","false");
            JSONObject history = reader.read("/reading/bookapi/read_history/list/v", query, verified);
            JSONObject shelf = reader.read("/reading/bookapi/bookshelf/video/list/v", null, verified);
            requireList(history,"data_list"); requireList(shelf,"video_shelf_info");
            return verified;
        } catch (QrLoginManager.QrException error) {
            throw error;
        } catch (IOException error) {
            // Upstream exceptions can carry authorization material; keep them private.
            throw new QrLoginManager.QrException("QR_MOBILE_SESSION_UNAVAILABLE",
                    "手机账号连接暂时中断，正在重试", true);
        } catch (Exception error) {
            throw unavailable("扫码账号暂时无法连接手机账号数据，请重试或使用验证码登录");
        }
    }

    private static boolean ok(JSONObject data) {
        return data != null && data.has("code") && "0".equals(String.valueOf(data.opt("code")));
    }

    private static void requireList(JSONObject payload, String key) throws IOException {
        JSONObject data = payload == null ? null : payload.optJSONObject("data");
        if (!ok(payload) || data == null || (data.has(key) && !(data.opt(key) instanceof JSONArray))) {
            throw unavailable("扫码账号暂时无法读取手机历史或收藏，请重试或使用验证码登录");
        }
    }

    private static QrLoginManager.QrException unavailable(String message) {
        return new QrLoginManager.QrException("QR_MOBILE_SESSION_UNAVAILABLE",message,false);
    }
}
