package net.clanimg.worldsGUI.guiconfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final String SLOT_RANGE_KEY = "slot-range";
    private static final String DEFAULT_MATERIAL = "GRAY_STAINED_GLASS_PANE";

    private GuiConfigLoader() {
    }

    public static GuiConfig load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "guis.yml");
        if (!file.exists()) {
            plugin.saveResource("guis.yml", false);
        }

        YamlConfiguration root = new YamlConfiguration();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            root.load(reader);
        } catch (IOException | InvalidConfigurationException ex) {
            throw new GuiConfigException("guis.yml konnte nicht gelesen/geparst werden: " + ex.getMessage(), ex);
        }

        return loadFromYaml(root, plugin.getLogger());
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
        GuiSlotRangeDefinition slotRange = null;

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
                } else if (key.equalsIgnoreCase(SLOT_RANGE_KEY)) {
                    slotRange = parseSlotRange(guiId, entrySection, size, errors, warnings);
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

        return new GuiDefinition(guiId, size, title, position, rowSlots, slotRange, autoContentSource);
    }

    private static GuiSlotDefinition parseSlot(String guiId, String slotKey, ConfigurationSection section, List<String> errors, List<String> warnings) {
        String material = section.getString("material", DEFAULT_MATERIAL);
        String title = section.getString("title", "");
        GuiAction action = null;

        ConfigurationSection actionSection = section.getConfigurationSection("action");
        if (actionSection != null) {
            action = parseAction(guiId, slotKey, actionSection, errors, warnings);
        }

        return new GuiSlotDefinition(material, title, action);
    }

    private static GuiSlotRangeDefinition parseSlotRange(String guiId, ConfigurationSection section, int guiSize, List<String> errors, List<String> warnings) {
        int from = section.getInt("from", -1);
        int to = section.getInt("to", -1);

        if (from < 0 || to < 0) {
            errors.add("GUI '" + guiId + "': slot-range benötigt 'from' und 'to'.");
            return null;
        }
        if (from > to) {
            errors.add("GUI '" + guiId + "': slot-range 'from' (" + from + ") ist größer als 'to' (" + to + ").");
            return null;
        }
        if (to >= guiSize) {
            errors.add("GUI '" + guiId + "': slot-range " + from + "-" + to + " liegt außerhalb der gui-size " + guiSize + ".");
            return null;
        }

        String source = section.getString("source");
        String filter = section.getString("filter");
        String material = section.getString("material", DEFAULT_MATERIAL);
        String title = section.getString("title", "");
        GuiAction action = null;

        ConfigurationSection actionSection = section.getConfigurationSection("action");
        if (actionSection != null) {
            action = parseAction(guiId, SLOT_RANGE_KEY, actionSection, errors, warnings);
        }

        return new GuiSlotRangeDefinition(from, to, source, filter, material, title, action);
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

        TriggerType type;
        try {
            type = TriggerType.valueOf(rawType.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
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
                yield GuiTrigger.command(command, clicks);
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
                String anvilTitle = triggerSection.getString("anvil-title");
                String targetGuiId = triggerSection.getString("gui-id");
                String paramKey = triggerSection.getString("param-key");
                if (anvilTitle == null || anvilTitle.isBlank() || targetGuiId == null || targetGuiId.isBlank()
                    || paramKey == null || paramKey.isBlank()) {
                    errors.add("GUI '" + guiId + "', Slot '" + slotKey + "': trigger 'anvil-input' benötigt 'anvil-title', 'gui-id' und 'param-key'.");
                    yield null;
                }
                yield GuiTrigger.anvilInput(anvilTitle, targetGuiId, paramKey);
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
            if (gui.slotRange() != null && gui.slotRange().action() != null) {
                checkGuiReferences(
                    gui,
                    Stream.of(new GuiSlotDefinition(null, null, gui.slotRange().action())),
                    guis,
                    errors,
                    "slot-range"
                );
            }
        }
    }

    private static void checkOverlap(GuiDefinition gui, List<String> errors) {
        if (gui.slotRange() == null || gui.rowSlots().isEmpty()) {
            return;
        }

        for (Integer rowSlot : gui.rowSlots().keySet()) {
            int absolute;
            try {
                absolute = SlotMapper.mapRowSlotToAbsolute(rowSlot, gui.position(), gui.size());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (absolute >= gui.slotRange().from() && absolute <= gui.slotRange().to()) {
                errors.add(
                    "GUI '" + gui.id() + "': slot-" + rowSlot + " (absoluter Slot " + absolute + ") überschneidet sich mit slot-range "
                        + gui.slotRange().from() + "-" + gui.slotRange().to() + "."
                );
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
                if ((trigger.type() == TriggerType.OPEN_GUI || trigger.type() == TriggerType.SELECT || trigger.type() == TriggerType.ANVIL_INPUT)
                    && trigger.guiId() != null && !trigger.guiId().isBlank()
                    && !guis.containsKey(trigger.guiId().toLowerCase(Locale.ROOT))) {
                    errors.add("GUI '" + gui.id() + "' (" + location + "): referenzierte gui-id '" + trigger.guiId() + "' existiert nicht.");
                }
            }
        });
    }
}
