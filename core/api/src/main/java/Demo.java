import java.util.ArrayList;
import java.util.List;

public class Demo {
  private static final int MAXIMUM_FUNCTION = 26 * (26 + 10);
  private static final char[] ALPHA_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
  private static final char[] ALPHANUMERIC_CHARACTERS =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

  public static void main(String[] args) {
    var a = new ArrayList<String>();

    for (int i = 0; i < MAXIMUM_FUNCTION; i++) {
      var b = generateNextFunctionKey(a);
      a.add(b);
    }
    System.out.println(11);
  }

  public static String generateNextFunctionKey(List<String> keys) {
    int first = 0, second = 0;
    while (true) {
      var key = "" + ALPHA_CHARACTERS[first] + ALPHANUMERIC_CHARACTERS[second];
      if (!keys.contains(key)) {
        return key;
      }
      second = (second + 1) % ALPHANUMERIC_CHARACTERS.length;
      if (second == 0) ++first;
    }
  }
}
