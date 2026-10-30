package net.clanimg.worldsGUI.guiconfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lädt und validiert guis.yml (siehe Kommentarblock am Dateianfang für die vollständige Grammatik).
 * Kritische Fehler (überlappende Slot-Bereiche, unbekannte gui-id-Referenzen, fehlende Pflichtfelder,
 * ungültige Werte) führen zu einer {@link GuiConfigException}. Nicht-kritische Probleme (z.B. fehlende
 * optionale Felder mit sinnvollem Default) werden nur über den Plugin-Logger als Warnung ausgegeben.
 */
public final class GuiConfigLoader {
    private static final Pattern ROW_SLOT_KEY = Pattern.compile("^slot-([1-9])$");
    private static final Pattern SLOT_RANGE_KEY_PATTERN = Pattern.compile("^slot-range(-[A-Za-z0-9_-]+)?$", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_MATERIAL = "GRAY_STAINED_GLASS_PANE";

    private GuiConfigLoader() {
    }

    /**
     * SHA-256 früher ausgelieferter guis.yml-Versionen. Liegt auf einem Server noch eine dieser Dateien
     * unverändert, wird sie beim Laden durch die aktuelle Version aus dem Jar ersetzt (mit Sicherungskopie).
     * Angepasste Dateien bleiben unangetastet. Beim Ändern der guis.yml den Hash der alten Version hier ergänzen.
     */
    static final Set<String> SUPERSEDED_DEFAULT_HASHES = Set.of(
        "c96d88033aabc0834c94a34c4a7c8bc3e31271084d60a0f5e95c60b183b09387",
        "0835323c8844e77df957a10009205470b6a5b8e9300a156d94b2fee9074a30db",
        "1bb5f75a91b8d041afab94287402554837df1aa7b9f908596fd2f9221966bfd3",
        "1c9e09101eb1721b1e22c0cede8913002407a39a932b606425d8d0d8d7a0e22f",
        "32e84c7005e12b8d146a0a6f18b50268c202857c2ab5c4f87ba08af250e2741d",
        "60d1c2beb62315395df9e54ae974aadc35bb2d9baa5cedb3de6500e2b8b24d13",
        "fa577f983ae8d5a1e281a2a508ec2114ef4604488fea669d04c050b3b262b11f",
        "faa3036cafbc665fcf32278a79bd59aa2300943ea2aa8708e6cfe4efabc8f176"
    );

    public static GuiConfig load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "guis.yml");
        if (!file.exists()) {
            plugin.saveResource("guis.yml", false);
        } else {
            replaceSupersededDefault(plugin, file);
        }

        YamlConfiguration root = new YamlConfiguration();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            root.load(reader);
        } catch (IOException | InvalidConfigurationException ex) {
            throw new GuiConfigException("guis.yml konnte nicht gelesen/geparst werden: " + ex.getMessage(), ex);
        }

        return loadFromYaml(root, plugin.getLogger());
    }

    /** Ersetzt eine unveränderte, veraltete Standard-guis.yml durch die aktuelle Version aus dem Jar. */
    private static void replaceSupersededDefault(JavaPlugin plugin, File file) {
        try {
            String hash = sha256(Files.readAllBytes(file.toPath()));
            if (!SUPERSEDED_DEFAULT_HASHES.contains(hash)) {
                return;
            }
            Path backup = file.toPath().resolveSibling("guis.yml.bak-" + LocalDate.now());
            Files.copy(file.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);
            plugin.saveResource("guis.yml", true);
            plugin.getLogger().info("guis.yml war eine unveränderte ältere Standardversion und wurde aktualisiert"
                + " (Sicherung: " + backup.getFileName() + ").");
        } catch (IOException ex) {
            plugin.getLogger().warning("guis.yml konnte nicht auf die neue Standardversion geprüft werden: " + ex.getMessage());
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Parst und validiert eine bereits geladene guis.yml-Struktur. Getrennt von {@link #load(JavaPlugin)},
     * damit die Validierungslogik auch ohne laufenden Server (z.B. in Tests) ausgeführt werden kann.
     */
    public static GuiConfig loadFromYaml(YamlConfiguration root, java.util.logging.Logger logger) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, GuiDefinition> guis = new LinkedHashMap<>();

        ConfigurationSection guisSection = root.getConfigurationSection("guis");
        if (guisSection == null) {
            throw new GuiConfigException("guis.yml enthält keinen 'guis:'-Abschnitt.");
        }

        for (String guiId : guisSection.getKeys(false)) {
            ConfigurationSection guiSection = guisSection.getConfigurationSection(guiId);
            if (guiSection == null) {
                errors.add("GUI '" + guiId + "': Eintrag ist kein gültiger Abschnitt.");
                continue;
            }
            GuiDefinition definition = parseGui(guiId, guiSection, errors, warnings);
            if (definition != null) {
                guis.put(guiId.toLowerCase(Locale.ROOT), definition);
            }
        }

        crossValidate(guis, errors);

        for (String warning : warnings) {
            logger.warning("[guis.yml] " + warning);
        }

        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder("guis.yml enthält " + errors.size() + " Fehler:");
            for (String error : errors) {
                message.append("\n - ").append(error);
            }
            throw new GuiConfigException(message.toString());
        }

        return new GuiConfig(guis);
    }

    private static GuiDefinition parseGui(String guiId, ConfigurationSection section, List<String> errors, List<String> warnings) {
        int size = section.getInt("gui-size", -1);
        if (!SlotMapper.isValidGuiSize(size)) {
            errors.add("GUI '" + guiId + "': ungültige gui-size " + size + " (muss Vielfaches von 9 zwischen 9 und 54 sein).");
            return null;
        }

        String title = section.getString("title", "");

        Map<Integer, GuiSlotDefinition> rowSlots = new LinkedHashMap<>();
        Map<String, GuiSlotRangeDefinition> slotRanges = new LinkedHashMap<>();

        ConfigurationSection slotsSection = section.getConfigurationSection("slots");
        if (slotsSection != null) {
            for (String key : slotsSection.getKeys(false)) {
                ConfigurationSection entrySection = slotsSection.getConfigurationSection(key);
                if (entrySection == null) {
                    warnings.add("GUI '" + guiId + "': Slot-Eintrag '" + key + "' ist kein gültiger Abschnitt und wird ignoriert.");
                    continue;
                }

                Matcher rowMatcher = ROW_SLOT_KEY.matcher(key);
                if (rowMatcher.matches()) {
                    int rowSlot = Integer.parseInt(rowMatcher.group(1));
                    GuiSlotDefinition slotDefinition = parseSlot(guiId, key, entrySection, errors, warnings);
                    if (slotDefinition != null) {
                        rowSlots.put(rowSlot, slotDefinition);
                    }
                } else if (SLOT_RANGE_KEY_PATTERN.matcher(key).matches()) {
                    GuiSlotRangeDefinition slotRange = parseSlotRange(guiId, key, entrySection, size, errors, warnings);
                    if (slotRange != null) {
                        slotRanges.put(key, slotRange);
                    }
                } else {
                    warnings.add("GUI '" + guiId + "': unbekannter Slot-Schlüssel '" + key + "' wird ignoriert.");
                }
            }
        }

        String positionRaw = section.getString("position");
        boolean positionProvided = positionRaw != null && !positionRaw.isBlank();
        GuiPosition position = null;
        if (positionProvided) {
            try {
                position = GuiPosition.valueOf(positionRaw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                errors.add("GUI '" + guiId + "': ungültige position '" + positionRaw + "' (erlaubt: bottom, middle).");
            }
        }
        if (position == null) {
            if (!positionProvided && !rowSlots.isEmpty()) {
                warnings.add("GUI '" + guiId + "': keine 'position' angegeben, obwohl slot-1..9 verwendet wird. Standardwert 'middle' wird genutzt.");
            }
            position = GuiPosition.MIDDLE;
        }

        for (Integer rowSlot : rowSlots.keySet()) {
            try {
                SlotMapper.mapRowSlotToAbsolute(rowSlot, position, size);
            } catch (IllegalArgumentException ex) {
                errors.add("GUI '" + guiId + "': slot-" + rowSlot + " kann nicht abgebildet werden: " + ex.getMessage());
            }
        }

        String autoContentSource = null;
        if ("my-worlds".equalsIgnoreCase(guiId)) {
            autoContentSource = "own-worlds";
        } else if ("invited-worlds".equalsIgnoreCase(guiId)) {
            autoContentSource = "invited-worlds";
        }

        String returnGuiId = section.getString("return-gui-id");
        GuiPagination pagination = parsePagination(guiId, section.getConfigurationSection("pagination"), rowSlots.keySet(), errors);

        return new GuiDefinition(guiId, size, title, position, rowSlots, new ArrayList<>(slotRanges.values()), autoContentSource, returnGuiId, pagination);
    }

    /**
     * pagination:
     *   previous: { slot: 2, material: "ARROW", title: "...", lore: [...] }
     *   next:     { slot: 8, material: "ARROW", title: "...", lore: [...] }
     * Die Slots sind Zeilen-Slots (1-9) und dürfen nicht mit slot-1..9 belegt sein.
     */
    private static GuiPagination parsePagination(String guiId, ConfigurationSection section, java.util.Set<Integer> usedRowSlots, List<String> errors) {
        if (section == null) {
            return null;
        }
        ConfigurationSection previous = section.getConfigurationSection("previous");
        ConfigurationSection next = section.getConfigurationSection("next");
        if (previous == null || next == null) {
            errors.add("GUI '" + guiId + "': 'pagination' benötigt 'previous' und 'next'.");
            return null;
        }
        int previousSlot = previous.getInt("slot", -1);
        int nextSlot = next.getInt("slot", -1);
        for (int slot : new int[] {previousSlot, nextSlot}) {
            if (slot < 1 || slot > 9) {
                errors.add("GUI '" + guiId + "': pagination-slot muss zwischen 1 und 9 liegen (ist " + slot + ").");
                return null;
            }
            if (usedRowSlots.contains(slot)) {
                errors.add("GUI '" + guiId + "': pagination-slot " + slot + " ist bereits als slot-" + slot + " belegt.");
                return null;
            }
        }
        if (previousSlot == nextSlot) {
            errors.add("GUI '" + guiId + "': pagination 'previous' und 'next' dürfen nicht denselben Slot nutzen.");
            return null;
        }
        return new GuiPagination(
            previousSlot, previous.getString("material", "ARROW"), previous.getString("title", "&eVorherige Seite"), previous.getStringList("lore"),
            nextSlot, next.getString("material", "ARROW"), next.getString("title", "&eNächste Seite"), next.getStringList("lore")
        );
    }

    private static GuiSlotDefinition parseSlot(String guiId, String slotKey, ConfigurationSection section, List<String> errors, List<String> warnings) {
        String material = section.getString("material", DEFAULT_MATERIAL);
        String title = section.getString("title", "");
        List<String> lore = section.getStringList("lore");
        String requiresPlugin = section.getString("requires-plugin");
        GuiAction action = null;

        ConfigurationSection actionSection = section.getConfigurationSection("action");
        if (actionSection != null) {
            action = parseAction(guiId, slotKey, actionSection, errors, warnings);
        }

        return new GuiSlotDefinition(material, title, lore, action, requiresPlugin);
    }

    private static GuiSlotRangeDefinition parseSlotRange(String guiId, String rangeKey, ConfigurationSection section, int guiSize, List<String> errors, List<String> warnings) {
        int from = section.getInt("from", -1);
        int to = section.getInt("to", -1);

        if (from < 0 || to < 0) {
            errors.add("GUI '" + guiId + "': " + rangeKey + " benötigt 'from' und 'to'.");
            return null;
        }
        if (from > to) {
            errors.add("GUI '" + guiId + "': " + rangeKey + " 'from' (" + from + ") ist größer als 'to' (" + to + ").");
            return null;
        }
        if (to >= guiSize) {
            errors.add("GUI '" + guiId + "': " + rangeKey + " " + from + "-" + to + " liegt außerhalb der gui-size " + guiSize + ".");
            return null;
        }

        String source = section.getString("source");
        String filter = section.getString("filter");
        String material = section.getString("material", DEFAULT_MATERIAL);
        String title = section.getString("title", "");
        List<String> lore = section.getStringList("lore");
        String emptyMaterial = section.getString("empty-material");
        String emptyTitle = section.getString("empty-title", "");
        String requiresPlugin = section.getString("requires-plugin");
        GuiAction action = null;

        ConfigurationSection actionSection = section.getConfigurationSection("action");
        if (actionSection != null) {
            action = parseAction(guiId, rangeKey, actionSection, errors, warnings);
        }

        return new GuiSlotRangeDefinition(from, to, source, filter, material, title, lore, emptyMaterial, emptyTitle, action, requiresPlugin);
    }

    private static GuiAction parseAction(String guiId, String slotKey, ConfigurationSection actionSection, List<String> errors, List<String> warnings) {
        if (actionSection.contains("trigger")) {
            GuiTrigger trigger = parseTrigger(guiId, slotKey, actionSection, errors);
            return trigger == null ? null : GuiAction.anyClick(trigger);
        }

        ConfigurationSection leftSection = actionSection.getConfigurationSection("left-click");
        ConfigurationSection rightSection = actionSection.getConfigurationSection("right-click");

        if (leftSection == null && rightSection == null) {
            warnings.add("GUI '" + guiId + "', Slot '" + slotKey + "': 'action' definiert, aber kein trigger/left-click/right-click gefunden.");
            return null;
        }

        GuiTrigger leftTrigger = leftSection == null ? null : parseTrigger(guiId, slotKey + ".left-click", leftSection, errors);
        GuiTrigger rightTrigger = rightSection == null ? null : parseTrigger(guiId, slotKey + ".right-click", rightSection, errors);

        if (leftTrigger == null && rightTrigger == null) {
            return null;
        }

        return GuiAction.perClick(leftTrigger, rightTrigger);
    }

    private static GuiTrigger parseTrigger(String guiId, String slotKey, ConfigurationSection triggerSection, List<String> errors) {
        String rawType = triggerSection.getString("trigger");
        if (rawType == null || rawType.isBlank()) {
            errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': 'trigger'-Feld fehlt.");
            return null;
        }

        String normalizedType = rawType.trim();
        if (normalizedType.equalsIgnoreCase("dialog-input")) {
            // Alias: dialog-input verwendet intern dieselbe Laufzeitlogik wie der bisherige anvil-input Trigger.
            normalizedType = "anvil-input";
        }

        TriggerType type;
        try {
            type = TriggerType.valueOf(normalizedType.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': unbekannter trigger '" + rawType + "'.");
            return null;
        }

        return switch (type) {
            case COMMAND -> {
                String command = triggerSection.getString("command");
                if (command == null || command.isBlank()) {
                    errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': trigger 'command' benötigt ein 'command'-Feld.");
                    yield null;
                }
                int clicks = triggerSection.getInt("clicks", 1);
                boolean chatFeedback = triggerSection.getBoolean("chat-feedback", true);
                // optional: nach dem Command zu diesem GUI navigieren (z.B. zurück zur Übersicht)
                String targetGuiId = triggerSection.getString("gui-id");
                yield GuiTrigger.command(command, clicks, chatFeedback, targetGuiId);
            }
            case OPEN_GUI -> {
                String targetGuiId = triggerSection.getString("gui-id");
                if (targetGuiId == null || targetGuiId.isBlank()) {
                    errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': trigger 'open-gui' benötigt ein 'gui-id'-Feld.");
                    yield null;
                }
                yield GuiTrigger.openGui(targetGuiId, parseParams(triggerSection.getConfigurationSection("params")));
            }
            case RETURN -> GuiTrigger.returnTrigger();
            case SELECT -> {
                String selectId = triggerSection.getString("select-id");
                String selectValue = triggerSection.getString("select-value");
                if (selectId == null || selectId.isBlank() || selectValue == null || selectValue.isBlank()) {
                    errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': trigger 'select' benötigt 'select-id' und 'select-value'.");
                    yield null;
                }
                yield GuiTrigger.select(
                    selectId,
                    selectValue,
                    triggerSection.getString("gui-id"),
                    parseParams(triggerSection.getConfigurationSection("params"))
                );
            }
            case ANVIL_INPUT -> {
                String dialogTitle = triggerSection.getString("dialog-title");
                String anvilTitle = triggerSection.getString("anvil-title");
                String inputTitle = (dialogTitle != null && !dialogTitle.isBlank()) ? dialogTitle : anvilTitle;
                String targetGuiId = triggerSection.getString("gui-id");
                String paramKey = triggerSection.getString("param-key");
                if (inputTitle == null || inputTitle.isBlank() || targetGuiId == null || targetGuiId.isBlank()
                    || paramKey == null || paramKey.isBlank()) {
                    errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': trigger 'dialog-input' benötigt 'dialog-title' (oder 'anvil-title'), 'gui-id' und 'param-key'.");
                    yield null;
                }
                yield GuiTrigger.anvilInput(inputTitle, targetGuiId, paramKey, parseParams(triggerSection.getConfigurationSection("params")));
            }
            case TOGGLE -> {
                String toggleId = triggerSection.getString("toggle-id");
                String startState = triggerSection.getString("start-state");
                ConfigurationSection statesSection = triggerSection.getConfigurationSection("states");

                if (toggleId == null || toggleId.isBlank() || startState == null || startState.isBlank() || statesSection == null) {
                    errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': trigger 'toggle' benötigt 'toggle-id', 'start-state' und 'states'.");
                    yield null;
                }

                Map<String, GuiToggleState> states = new LinkedHashMap<>();
                for (String stateId : statesSection.getKeys(false)) {
                    ConfigurationSection stateSection = statesSection.getConfigurationSection(stateId);
                    if (stateSection == null) {
                        errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': toggle state '" + stateId + "' ist ungültig.");
                        continue;
                    }

                    String material = stateSection.getString("material");
                    String title = stateSection.getString("title", "");
                    String command = stateSection.getString("command");
                    boolean chatFeedback = stateSection.getBoolean("chat-feedback", true);
                    String next = stateSection.getString("next");

                    if (material == null || material.isBlank() || command == null || command.isBlank() || next == null || next.isBlank()) {
                        errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': toggle state '" + stateId + "' benötigt 'material', 'command' und 'next'.");
                        continue;
                    }

                    states.put(stateId, new GuiToggleState(stateId, material, title, command, chatFeedback, next));
                }

                if (!states.containsKey(startState)) {
                    errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': toggle start-state '" + startState + "' existiert nicht in 'states'.");
                    yield null;
                }

                for (GuiToggleState state : states.values()) {
                    if (!states.containsKey(state.next())) {
                        errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': toggle state '" + state.id() + "' verweist mit next='" + state.next() + "' auf einen unbekannten Zustand.");
                    }
                }

                if (states.isEmpty()) {
                    errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': trigger 'toggle' enthält keine gültigen states.");
                    yield null;
                }

                String stateSource = triggerSection.getString("state-source");
                yield GuiTrigger.toggle(toggleId, startState, states, stateSource == null || stateSource.isBlank() ? null : stateSource.trim());
            }
        };
    }

    private static Map<String, String> parseParams(ConfigurationSection paramsSection) {
        if (paramsSection == null) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String key : paramsSection.getKeys(false)) {
            Object value = paramsSection.get(key);
            if (value != null) {
                params.put(key, String.valueOf(value));
            }
        }
        return params;
    }

    private static void crossValidate(Map<String, GuiDefinition> guis, List<String> errors) {
        for (GuiDefinition gui : guis.values()) {
            checkOverlap(gui, errors);
            checkGuiReferences(gui, gui.rowSlots().values().stream(), guis, errors, "slot-1..9");
            for (GuiSlotRangeDefinition range : gui.slotRanges()) {
                if (range.action() == null) {
                    continue;
                }
                checkGuiReferences(
                    gui,
                    Stream.of(new GuiSlotDefinition(null, null, range.action())),
                    guis,
                    errors,
                    "slot-range " + range.from() + "-" + range.to()
                );
            }
            if (gui.returnGuiId() != null && !gui.returnGuiId().isBlank()
                && !guis.containsKey(gui.returnGuiId().toLowerCase(Locale.ROOT))) {
                errors.add("GUI '" + gui.id() + "': 'return-gui-id' verweist auf unbekanntes GUI '" + gui.returnGuiId() + "'.");
            }
        }
    }

    private static void checkOverlap(GuiDefinition gui, List<String> errors) {
        List<GuiSlotRangeDefinition> ranges = gui.slotRanges();

        for (Integer rowSlot : gui.rowSlots().keySet()) {
            int absolute;
            try {
                absolute = SlotMapper.mapRowSlotToAbsolute(rowSlot, gui.position(), gui.size());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            for (GuiSlotRangeDefinition range : ranges) {
                if (absolute >= range.from() && absolute <= range.to()) {
                    errors.add(
                        "GUI '" + gui.id() + "': slot-" + rowSlot + " (absoluter Slot " + absolute + ") überschneidet sich mit slot-range "
                            + range.from() + "-" + range.to() + "."
                    );
                }
            }
        }

        for (int i = 0; i < ranges.size(); i++) {
            for (int j = i + 1; j < ranges.size(); j++) {
                GuiSlotRangeDefinition first = ranges.get(i);
                GuiSlotRangeDefinition second = ranges.get(j);
                if (first.from() <= second.to() && second.from() <= first.to()) {
                    errors.add(
                        "GUI '" + gui.id() + "': slot-range " + first.from() + "-" + first.to() + " überschneidet sich mit slot-range "
                            + second.from() + "-" + second.to() + "."
                    );
                }
            }
        }
    }

    private static void checkGuiReferences(
        GuiDefinition gui,
        Stream<GuiSlotDefinition> slots,
        Map<String, GuiDefinition> guis,
        List<String> errors,
        String location
    ) {
        slots.forEach(slot -> {
            GuiAction action = slot.action();
            if (action == null) {
                return;
            }
            for (GuiTrigger trigger : Arrays.asList(action.anyClickTrigger(), action.leftClickTrigger(), action.rightClickTrigger())) {
                if (trigger == null) {
                    continue;
                }
                if ((trigger.type() == TriggerType.OPEN_GUI || trigger.type() == TriggerType.SELECT
                    || trigger.type() == TriggerType.ANVIL_INPUT || trigger.type() == TriggerType.COMMAND)
                    && trigger.guiId() != null && !trigger.guiId().isBlank()
                    && !guis.containsKey(trigger.guiId().toLowerCase(Locale.ROOT))) {
                    errors.add("GUI '" + gui.id() + "' (" + location + "): referenzierte gui-id '" + trigger.guiId() + "' existiert nicht.");
                }
            }
        });
    }
}
