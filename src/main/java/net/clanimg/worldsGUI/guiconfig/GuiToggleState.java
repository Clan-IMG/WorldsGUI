package net.clanimg.worldsGUI.guiconfig;

/**
 * Ein einzelner Zustand eines toggle-Triggers.
 */
public final class GuiToggleState {
    private final String id;
    private final String material;
    private final String title;
    private final String command;
    private final String next;

    public GuiToggleState(String id, String material, String title, String command, String next) {
        this.id = id;
        this.material = material;
        this.title = title;
        this.command = command;
        this.next = next;
    }

    public String id() {
        return id;
    }

    public String material() {
        return material;
    }

    public String title() {
        return title;
    }

    public String command() {
        return command;
    }

    public String next() {
        return next;
    }
}