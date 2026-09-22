import com.kevtrinh.rabbitphone.ButtonGestures;
import java.util.ArrayList;
import java.util.List;

public class ButtonGesturesTest {
    static class Task { Runnable action; long at; Task(Runnable a, long t) { action=a; at=t; } }
    static class Harness implements ButtonGestures.Scheduler, ButtonGestures.Actions {
        long now;
        List<Task> tasks = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        ButtonGestures gestures = new ButtonGestures(this, this);
        public long now() { return now; }
        public void postDelayed(Runnable r,long d) { tasks.add(new Task(r,now+d)); }
        public void remove(Runnable r) { tasks.removeIf(t -> t.action == r); }
        void advance(long delta) {
            long target=now+delta;
            while (true) {
                Task next=null;
                for(Task t:tasks) if(t.at<=target && (next==null || t.at<next.at)) next=t;
                if(next==null) break;
                tasks.remove(next);now=next.at;next.action.run();
            }
            now=target;
        }
        void click() { gestures.down();advance(40);gestures.up();advance(40); }
        void expect(String... expected) {
            if (!actions.equals(java.util.Arrays.asList(expected)))
                throw new AssertionError(actions.toString());
        }
        public void onSingle() { actions.add("select"); }
        public void onDouble() { actions.add("camera"); }
        public void onHoldStart() { actions.add("record-start"); }
        public void onHoldEnd() { actions.add("record-stop"); }
        public void onRefresh() { actions.add("refresh"); }
        public void onShutdown() { actions.add("shutdown"); }
    }
    public static void main(String[] args) {
        Harness h=new Harness();h.click();h.expect();h.advance(280);h.expect("select");
        h=new Harness();h.click();h.click();h.advance(400);h.expect("camera");
        h=new Harness();h.gestures.down();h.advance(449);h.expect();h.advance(1);
        h.expect("record-start");h.advance(900);h.gestures.up();h.advance(400);h.expect("record-start","record-stop");
        h=new Harness();for(int i=0;i<5;i++)h.click();h.advance(400);h.expect("refresh");
        h=new Harness();for(int i=0;i<8;i++)h.click();h.advance(400);h.expect("shutdown");
        h=new Harness();h.gestures.down();h.gestures.down();h.advance(50);h.gestures.up();h.gestures.up();h.advance(400);h.expect("select");
        h=new Harness();h.click();h.gestures.cancel();h.advance(500);h.expect();
        h=new Harness();h.gestures.down();h.advance(450);h.gestures.cancel();h.advance(500);h.expect("record-start");
        for(int count:new int[]{3,4,6,7}) { h=new Harness();for(int i=0;i<count;i++)h.click();h.advance(500);h.expect(); }
        h=new Harness();h.now=1000;h.gestures.down(0);h.gestures.up(600);h.advance(1000);h.expect();
        h=new Harness();h.now=2700;
        for(int i=0;i<8;i++) { h.gestures.down(i*350);h.gestures.up(i*350+10); }
        h.advance(1000);h.expect("select");
        h=new Harness();h.now=2700;
        for(int i=0;i<8;i++) { h.gestures.down(i*80);h.gestures.up(i*80+10); }
        h.advance(1000);h.expect();
        System.out.println("15 gesture cases passed; no calls, recording, or shutdown executed.");
    }
}
