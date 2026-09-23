package com.kevtrinh.rabbitphone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Active task cards sit above a stable feature catalog. Visiting a feature is separate. */
public final class CardNavigation {
    private final ArrayList<String> catalog = new ArrayList<String>();
    private final ArrayList<String> opened = new ArrayList<String>();
    private String selected;

    public CardNavigation(List<String> ids) { setCatalog(ids); }

    public void setCatalog(List<String> ids) {
        ArrayList<String> checked = new ArrayList<String>();
        for (String id : ids) {
            if (id == null || id.isEmpty() || checked.contains(id)) {
                throw new IllegalArgumentException("Card IDs must be unique and nonempty");
            }
            checked.add(id);
        }
        catalog.clear();
        catalog.addAll(checked);
        opened.retainAll(catalog);
        if (!catalog.contains(selected)) selected = catalog.isEmpty() ? null : catalog.get(0);
    }

    public List<String> order() {
        ArrayList<String> result = new ArrayList<String>(opened);
        for (String id : catalog) if (!opened.contains(id)) result.add(id);
        return Collections.unmodifiableList(result);
    }

    public int selectedIndex() { return Math.max(0, order().indexOf(selected)); }
    public String selectedId() { return selected; }
    public boolean isOpened(String id) { return opened.contains(id); }

    public void select(int index) {
        List<String> ordered = order();
        if (ordered.isEmpty()) { selected = null; return; }
        selected = ordered.get(Math.max(0, Math.min(index, ordered.size() - 1)));
    }

    public void move(int delta) { select(selectedIndex() + delta); }

    public boolean visit(String id) {
        if (!catalog.contains(id)) return false;
        selected = id;
        return true;
    }

    public boolean open(String id) {
        if (!catalog.contains(id)) return false;
        opened.remove(id);
        opened.add(0, id);
        selected = id;
        return true;
    }

    /** Dismiss only the opened instance; its permanent feature card remains available. */
    public boolean close(String id) {
        if (!opened.contains(id)) return false;
        int oldIndex = order().indexOf(id);
        opened.remove(id);
        select(oldIndex);
        return true;
    }

    public List<String> openedIds() {
        return Collections.unmodifiableList(new ArrayList<String>(opened));
    }

    public void restoreOpened(List<String> ids) {
        opened.clear();
        for (String id : ids) if (catalog.contains(id) && !opened.contains(id)) opened.add(id);
    }
}
