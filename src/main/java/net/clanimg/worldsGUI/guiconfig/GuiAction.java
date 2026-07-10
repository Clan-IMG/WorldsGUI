package net.clanimg.worldsGUI.guiconfig;

/**
 * Ein "action"-Block in guis.yml. Entweder klick-unabhängig (anyClick) oder aufgeteilt
 * in left-click/right-click (siehe Kommentarblock "CLICK-VARIANTEN" in guis.yml).
 */
public final class GuiAction {
    private final GuiTrigger anyClick;
    private final GuiTrigger leftClick;
    private final GuiTrigger rightClick;

    private GuiAction(GuiTrigger anyClick, GuiTrigger leftClick, GuiTrigger rightClick) {
        this.anyClick = anyClick;
        this.leftClick = leftClick;
        this.rightClick = rightClick;
    }

    public static GuiAction anyClick(GuiTrigger trigger) {
        return new GuiAction(trigger, null, null);
    }

    public static GuiAction perClick(GuiTrigger leftClick, GuiTrigger rightClick) {
        return new GuiAction(null, leftClick, rightClick);
    }

    public GuiTrigger anyClickTrigger() {
        return anyClick;
    }

    public GuiTrigger leftClickTrigger() {
        return leftClick;
    }

    public GuiTrigger rightClickTrigger() {
        return rightClick;
    }

    /**
     * Löst den passenden Trigger für den tatsächlichen Klick auf.
     * Ein "anyClick"-Trigger hat immer Vorrang vor left-click/right-click.
     */
    public GuiTrigger resolve(boolean isLeftClick, boolean isRightClick) {
        if (anyClick != null) {
            return anyClick;
        }
        if (isLeftClick && leftClick != null) {
            return leftClick;
        }
        if (isRightClick && rightClick != null) {
            return rightClick;
        }
        return null;
    }

    public boolean isEmpty() {
        return anyClick == null && leftClick == null && rightClick == null;
    }
}
