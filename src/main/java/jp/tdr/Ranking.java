package jp.tdr;

import java.math.BigDecimal;
import java.util.*;

public final class Ranking {
  private Ranking() {}

  public record Value(String id, String name, BigDecimal balance) {
    public static Value of(String id, String name, double balance) {
      if (!Double.isFinite(balance)) throw new IllegalArgumentException("Invalid balance");
      return new Value(id, name == null ? id : name, BigDecimal.valueOf(balance));
    }
  }

  public record Row(String id, String name, BigDecimal balance, int rank) {}

  public static List<Row> sort(Collection<Value> values) {
    var sorted =
        values.stream()
            .sorted(Comparator.comparing(Value::balance).reversed().thenComparing(Value::id))
            .toList();
    List<Row> rows = new ArrayList<>();
    // 同額もUUID昇順で連番。日ごとの並び順を安定させる。
    for (int i = 0; i < sorted.size(); i++) {
      var v = sorted.get(i);
      rows.add(new Row(v.id(), v.name(), v.balance(), i + 1));
    }
    return List.copyOf(rows);
  }

  public static String comparison(Row row, List<Row> previous, boolean hasPrevious) {
    if (!hasPrevious) return "前日データなし";
    var old = previous.stream().filter(r -> r.id().equals(row.id())).findFirst();
    if (old.isEmpty()) return "前日対象外";
    var difference = row.balance().subtract(old.get().balance());
    int movement = old.get().rank() - row.rank();
    return "前日比 "
        + signed(difference)
        + " / 順位 "
        + (movement > 0 ? "↑" + movement : movement < 0 ? "↓" + -movement : "変動なし");
  }

  public static String signed(BigDecimal value) {
    return (value.signum() > 0 ? "+" : "") + value.toPlainString();
  }
}
