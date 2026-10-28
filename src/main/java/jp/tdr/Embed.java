package jp.tdr;

import com.google.gson.Gson;
import java.util.*;

public final class Embed {
  private Embed() {}

  public static String clean(String text) {
    return text.replace("@", "＠")
        .replace("`", "｀")
        .replace("*", "＊")
        .replace("_", "＿")
        .replace("~", "～");
  }

  public static String truncate(String text, int max) {
    if (text.length() <= max) return text;
    int end = max - 1;
    if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
    return text.substring(0, end) + "…";
  }

  public static String payload(String title, String description, int color) {
    return new Gson()
        .toJson(
            Map.of(
                "allowed_mentions",
                Map.of("parse", List.of()),
                "embeds",
                List.of(
                    Map.of(
                        "title",
                        truncate(title, 256),
                        "description",
                        truncate(description.isEmpty() ? "対象なし" : description, 4096),
                        "color",
                        color))));
  }

  public static List<String> ranking(
      String title,
      List<Ranking.Row> rows,
      List<Ranking.Row> previous,
      boolean hasPrevious,
      int limit,
      String week) {
    List<String> payloads = new ArrayList<>();
    StringBuilder page = new StringBuilder("対象日：" + week + "\n" + (hasPrevious ? "" : "前日データなし\n"));
    for (var row : rows.stream().limit(limit).toList()) {
      String line =
          row.rank()
              + ". "
              + truncate(clean(row.name()), 100)
              + "："
              + row.balance().toPlainString()
              + "\n"
              + Ranking.comparison(row, previous, hasPrevious)
              + "\n";
      if (page.length() + line.length() > 3800) {
        payloads.add(payload(title, page.toString(), 0x3498db));
        page.setLength(0);
      }
      page.append(line);
    }
    if (rows.isEmpty()) page.append("対象なし（連携状態・銀行設定を確認してください）");
    payloads.add(payload(title, page.toString(), 0x3498db));
    return List.copyOf(payloads);
  }
}
