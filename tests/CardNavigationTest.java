import com.kevtrinh.rabbitphone.CardNavigation;
import java.util.Arrays;
import java.util.Collections;

public final class CardNavigationTest {
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError();
    }
    private static void requireOrder(CardNavigation nav, String... expected) {
        require(nav.order().equals(Arrays.asList(expected)));
    }
    public static void main(String[] args) {
        CardNavigation nav = new CardNavigation(Arrays.asList("camera", "gallery", "timer", "recorder"));
        requireOrder(nav, "camera", "gallery", "timer", "recorder");
        require(nav.selectedId().equals("camera"));
        require(nav.visit("recorder"));
        require(nav.selectedId().equals("recorder") && !nav.isOpened("recorder"));
        requireOrder(nav, "camera", "gallery", "timer", "recorder");
        require(!nav.visit("unknown") && nav.selectedId().equals("recorder"));
        nav.visit("camera");
        nav.move(-1); require(nav.selectedIndex() == 0 && nav.selectedId().equals("camera"));
        nav.move(100); require(nav.selectedIndex() == 3 && nav.selectedId().equals("recorder"));
        nav.select(1); require(nav.selectedId().equals("gallery"));
        require(nav.open("timer"));
        requireOrder(nav, "timer", "camera", "gallery", "recorder");
        require(nav.selectedId().equals("timer") && nav.selectedIndex() == 0);
        require(nav.visit("gallery"));
        requireOrder(nav, "timer", "camera", "gallery", "recorder");
        require(nav.isOpened("timer") && !nav.isOpened("gallery"));
        require(nav.open("recorder"));
        requireOrder(nav, "recorder", "timer", "camera", "gallery");
        require(nav.open("timer"));
        requireOrder(nav, "timer", "recorder", "camera", "gallery");
        require(nav.openedIds().equals(Arrays.asList("timer", "recorder")));
        require(!nav.open("unknown") && !nav.close("gallery"));
        require(nav.close("timer"));
        requireOrder(nav, "recorder", "camera", "gallery", "timer");
        require(nav.selectedId().equals("recorder") && nav.selectedIndex() == 0);
        require(nav.close("recorder") && nav.selectedId().equals("camera"));
        requireOrder(nav, "camera", "gallery", "timer", "recorder");
        nav.restoreOpened(Arrays.asList("missing", "recorder", "recorder", "camera"));
        require(nav.openedIds().equals(Arrays.asList("recorder", "camera")));
        requireOrder(nav, "recorder", "camera", "gallery", "timer");
        require(nav.selectedId().equals("camera") && nav.selectedIndex() == 1);
        nav.setCatalog(Arrays.asList("camera", "timer"));
        require(nav.openedIds().equals(Collections.singletonList("camera")));
        requireOrder(nav, "camera", "timer");
        require(nav.selectedId().equals("camera"));
        boolean refused = false;
        try { nav.setCatalog(Arrays.asList("camera", "camera")); }
        catch (IllegalArgumentException expected) { refused = true; }
        require(refused && nav.order().equals(Arrays.asList("camera", "timer")));
        nav.setCatalog(Collections.<String>emptyList()); nav.move(1);
        require(nav.selectedId() == null && nav.order().isEmpty());
        System.out.println("Card navigation cases passed: feature visits, active ordering, selection, dismissal, restore, invalid catalog.");
    }
}
