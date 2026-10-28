package com.lancinema.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bookmarks persist independently of the source device being online. */
final class FavoriteStore {
  private final Context context;

  FavoriteStore(Context context) {
    this.context = context;
  }

  synchronized JSONArray all() {
    try {
      return new JSONArray(context.getSharedPreferences("favorites", 0).getString("videos", "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  synchronized boolean contains(String key) {
    JSONArray entries = all();
    for (int i = 0; i < entries.length(); i++)
      if (entries.optJSONObject(i) != null && key.equals(entries.optJSONObject(i).optString("key")))
        return true;
    return false;
  }

  synchronized JSONArray toggle(JSONObject entry) {
    String key = entry.optString("key");
    if (key.isEmpty() || entry.optJSONObject("file") == null) return all();
    JSONArray entries = all(), result = new JSONArray();
    boolean found = false;
    for (int i = 0; i < entries.length(); i++) {
      JSONObject current = entries.optJSONObject(i);
      if (current == null) continue;
      if (key.equals(current.optString("key"))) found = true;
      else result.put(current);
    }
    if (!found) {
      JSONArray added = new JSONArray().put(entry);
      for (int i = 0; i < result.length(); i++) added.put(result.optJSONObject(i));
      result = added;
    }
    context
        .getSharedPreferences("favorites", 0)
        .edit()
        .putString("videos", result.toString())
        .apply();
    return result;
  }
}
