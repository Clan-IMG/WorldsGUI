# Originalcode vor Diagnoseinstrumentierung

Stand b231e0d. Relevante Methoden vollständig, GUI-/Switch-Einstiege als gekennzeichnete Ausschnitte. Zeilennummern beziehen sich auf diesen Originalstand.

## src/main/resources/guis.yml, Zeilen 467–524

```java
  467:   # die Auswahl (world-type) bleibt in der Spieler-Session gespeichert
  468:   # und wird später beim finalen Command in select-server ausgelesen.
  469:   create-world:
  470:     gui-size: 27
  471:     title: "Welt erstellen"
  472:     position: middle
  473:     slots:
  474:       slot-3:
  475:         material: "SPRUCE_DOOR"
  476:         title: "Zurück"
  477:         action:
  478:           trigger: "return"
  479:       slot-5:
  480:         material: "FEATHER"
  481:         title: "Void"
  482:         action:
  483:           trigger: "select"
  484:           select-id: "world-type"
  485:           select-value: "VOID"
  486:           gui-id: "select-server" # nach Auswahl direkt weiter zum nächsten GUI
  487:       slot-7:
  488:         material: "GRASS_BLOCK"
  489:         title: "Flatworld"
  490:         action:
  491:           trigger: "select"
  492:           select-id: "world-type"
  493:           select-value: "FLAT"
  494:           gui-id: "select-server" # nach Auswahl direkt weiter zum nächsten GUI
  495: 
  496:   # Schritt 2 von 2 beim Welt-Erstellen: Server auswählen.
  497:   # Führt %selection:world-type% (aus create-world) und
  498:   # %selection:server% (aus diesem GUI) im finalen Command zusammen.
  499:   select-server:
  500:     gui-size: 27
  501:     title: "Server auswählen"
  502:     position: middle
  503:     slots:
  504:       slot-1:
  505:         material: "SPRUCE_DOOR"
  506:         title: "Zurück"
  507:         action:
  508:           trigger: "return"
  509:       slot-range:
  510:         from: 3
  511:         to: 8 # keine zweite Seite, da wenige Server erwartet werden - sonst Abschneiden bekannter Kompromiss
  512:         source: "servers" # Plugin holt sich intern die Liste verfügbarer Server
  513:         material: "GRASS_BLOCK"
  514:         title: "%server_name%" # Anzeigename pro generiertem Item
  515:         action:
  516:           trigger: "select"
  517:           select-id: "server"
  518:           select-value: "%server_id%" # interne Server-ID (nicht der Anzeigename!)
  519:           gui-id: "confirm"
  520:           params:
  521:             # /nav create <world-type> <server-id>
  522:             # Weltname wird NICHT übergeben, sondern serverseitig automatisch
  523:             # generiert im Format <spieler>-<id>
  524:             confirm-command: "nav create %selection:world-type% %selection:server%"
```

## src/main/resources/guis.yml, Zeilen 246–261

```java
  246:   # Generisches Bestätigungs-GUI. %confirm-command% wird beim Öffnen
  247:   # über "params.confirm-command" (aus dem aufrufenden GUI) ersetzt.
  248:   # Wird für alle destruktiven/wichtigen Aktionen genutzt (z.B. Löschen,
  249:   # finale Welt-Erstellung, Umbenennen), NICHT für harmlose/reversible
  250:   # Aktionen wie trust/untrust.
  251:   confirm:
  252:     gui-size: 27
  253:     title: "Bist du dir sicher?"
  254:     slots:
  255:       slot-5:
  256:         material: "BARRIER"
  257:         title: "Bestätigen"
  258:         clicks: 1
  259:         action:
  260:           trigger: "command"
  261:           command: "%confirm-command%"
```

## src/main/java/net/clanimg/worldsGUI/listener/InventoryListener.java, Zeilen 1–19

```java
    1: package net.clanimg.worldsGUI.listener;
    2: 
    3: import net.clanimg.worldsGUI.gui.GuiManager;
    4: import org.bukkit.event.EventHandler;
    5: import org.bukkit.event.Listener;
    6: import org.bukkit.event.inventory.InventoryClickEvent;
    7: 
    8: public final class InventoryListener implements Listener {
    9:     private final GuiManager guiManager;
   10: 
   11:     public InventoryListener(GuiManager guiManager) {
   12:         this.guiManager = guiManager;
   13:     }
   14: 
   15:     @EventHandler
   16:     public void onInventoryClick(InventoryClickEvent event) {
   17:         guiManager.handleInventoryClick(event);
   18:     }
   19: }
```

## src/main/java/net/clanimg/worldsGUI/command/NavCommand.java, Zeilen 39–52

```java
   39:         String sub = args[0].toLowerCase(Locale.ROOT);
   40:         switch (sub) {
   41:             case "create" -> {
   42:                 if (args.length < 2) {
   43:                     guiManager.sendWithPrefix(player, "usage.nav.create", "Usage: /nav create <ticketId>  ODER  /nav create <world-type> <server-id>", "%label%", label);
   44:                     return true;
   45:                 }
   46:                 if (args.length >= 3) {
   47:                     guiManager.executeNavCreateWizard(player, args[1], args[2]);
   48:                     return true;
   49:                 }
   50:                 guiManager.executeNavTicketCreate(player, args[1]);
   51:                 return true;
   52:             }
```

## src/main/java/net/clanimg/worldsGUI/guiruntime/TriggerDispatcher.java, Zeilen 43–103

```java
   43:      */
   44:     public void execute(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra, String triggerKey) {
   45:         if (trigger == null) {
   46:             return;
   47:         }
   48: 
   49:         switch (trigger.type()) {
   50:             case COMMAND -> executeCommand(player, session, trigger, extra, triggerKey);
   51:             case OPEN_GUI -> executeOpenGui(player, session, trigger, extra);
   52:             case RETURN -> executeReturn(player, session);
   53:             case SELECT -> executeSelect(player, session, trigger, extra);
   54:             case ANVIL_INPUT -> anvilInputRequester.request(player, session, trigger);
   55:             case TOGGLE -> executeToggle(player, session, trigger, extra);
   56:         }
   57:     }
   58: 
   59:     private void executeCommand(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra, String triggerKey) {
   60:         if (trigger.clicks() > 1 && triggerKey != null) {
   61:             int count = session.incrementClickCounter(triggerKey);
   62:             if (count < trigger.clicks()) {
   63:                 messageSender.send(
   64:                     player,
   65:                     "runtime.confirm-clicks-remaining",
   66:                     "&eNoch %remaining%x klicken zum Bestätigen.",
   67:                     "%remaining%",
   68:                     String.valueOf(trigger.clicks() - count)
   69:                 );
   70:                 return;
   71:             }
   72:             session.resetClickCounter(triggerKey);
   73:         }
   74: 
   75:         String expanded = PlaceholderExpander.expand(trigger.command(), player, session, extra);
   76:         if (expanded == null || expanded.isBlank()) {
   77:             messageSender.send(player, "runtime.command-missing-value", "&cDieser Command konnte nicht ausgeführt werden (fehlender Wert).");
   78:             return;
   79:         }
   80: 
   81:         boolean navigatesAfterCommand = trigger.guiId() != null && !trigger.guiId().isBlank();
   82:         if (!navigatesAfterCommand && trigger.chatFeedback()) {
   83:             player.closeInventory();
   84:         }
   85:         commandDispatcher.dispatch(player, expanded, trigger.chatFeedback());
   86: 
   87:         if (navigatesAfterCommand) {
   88:             session.pushCurrentToHistory();
   89:             session.setCurrentGuiId(trigger.guiId());
   90:             guiOpener.openGui(player, trigger.guiId());
   91:         }
   92:     }
   93: 
   94:     private void executeOpenGui(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra) {
   95:         Map<String, String> expandedParams = new LinkedHashMap<>();
   96:         for (Map.Entry<String, String> entry : trigger.params().entrySet()) {
   97:             expandedParams.put(entry.getKey(), PlaceholderExpander.expand(entry.getValue(), player, session, extra));
   98:         }
   99:         session.putGuiParams(expandedParams);
  100:         session.pushCurrentToHistory();
  101:         session.setCurrentGuiId(trigger.guiId());
  102:         guiOpener.openGui(player, trigger.guiId());
  103:     }
```

## src/main/java/net/clanimg/worldsGUI/guiruntime/TriggerDispatcher.java, Zeilen 129–147

```java
  129:     private void executeSelect(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra) {
  130:         String value = PlaceholderExpander.expand(trigger.selectValue(), player, session, extra);
  131:         session.putSelection(trigger.selectId(), value);
  132: 
  133:         Map<String, String> expandedParams = new LinkedHashMap<>();
  134:         for (Map.Entry<String, String> entry : trigger.params().entrySet()) {
  135:             expandedParams.put(entry.getKey(), PlaceholderExpander.expand(entry.getValue(), player, session, extra));
  136:         }
  137:         session.putGuiParams(expandedParams);
  138: 
  139:         if (trigger.guiId() != null && !trigger.guiId().isBlank()) {
  140:             session.pushCurrentToHistory();
  141:             session.setCurrentGuiId(trigger.guiId());
  142:             guiOpener.openGui(player, trigger.guiId());
  143:         }
  144:     }
  145: 
  146:     private void executeToggle(Player player, PlayerGuiSession session, GuiTrigger trigger, Map<String, String> extra) {
  147:         if (trigger.toggleStates().isEmpty()) {
```

## src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java, Zeilen 186–213

```java
  186:     public GuiManager(WorldsGUI plugin, WorldsRepository repository) {
  187:         this.plugin = plugin;
  188:         this.repository = repository;
  189:         this.worldKey = new NamespacedKey(plugin, "world_name");
  190:         this.messagesConfig = loadMessagesConfig();
  191:         this.worldDefaultsConfig = loadWorldDefaultsConfig();
  192:         this.backupService = new BackupService(plugin, repository, new BackupHooks());
  193:         this.triggerDispatcher = new TriggerDispatcher(
  194:             this::openGui,
  195:             (player, command, chatFeedback) -> {
  196:                 UUID playerId = player.getUniqueId();
  197:                 if (!chatFeedback) {
  198:                     suppressedChatFeedbackPlayers.add(playerId);
  199:                 }
  200:                 try {
  201:                     if (!dispatchRuntimeGuiCommand(player, command)) {
  202:                         Bukkit.dispatchCommand(player, command);
  203:                     }
  204:                 } finally {
  205:                     if (!chatFeedback) {
  206:                         suppressedChatFeedbackPlayers.remove(playerId);
  207:                     }
  208:                 }
  209:             },
  210:             this::requestAnvilInput,
  211:             (player, key, fallbackLiteral, replacements) -> sendNoPrefixConfigured(player, key, fallbackLiteral, replacements),
  212:             guiId -> guiConfig == null ? null : guiConfig.get(guiId).map(GuiDefinition::returnGuiId).orElse(null)
  213:         );
```

## src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java, Zeilen 232–254

```java
  232:     private boolean dispatchRuntimeGuiCommand(Player player, String rawCommand) {
  233:         if (rawCommand == null) {
  234:             return false;
  235:         }
  236: 
  237:         String command = rawCommand.trim();
  238:         if (command.isEmpty()) {
  239:             return false;
  240:         }
  241: 
  242:         String[] args = command.split("\\s+");
  243:         if (args.length < 5 || !args[0].equalsIgnoreCase("nav")) {
  244:             return false;
  245:         }
  246: 
  247:         boolean isMyWorldNamespace = args[1].equalsIgnoreCase("my-world") || args[1].equalsIgnoreCase("my-worlds");
  248:         if (!isMyWorldNamespace || !args[2].equalsIgnoreCase("delete") || !args[4].equalsIgnoreCase("confirm")) {
  249:             return false;
  250:         }
  251: 
  252:         executeNavMyWorldDeleteFromRuntimeGui(player, args[3]);
  253:         return true;
  254:     }
```

## src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java, Zeilen 695–715

```java
  695:     private void handleRuntimeGuiClick(Player player, InventoryClickEvent event, RuntimeGuiHolder holder) {
  696:         int slot = event.getRawSlot();
  697:         Inventory top = event.getView().getTopInventory();
  698:         if (slot < 0 || slot >= top.getSize()) {
  699:             return;
  700:         }
  701: 
  702:         RuntimeGuiHolder.ClickHandler clickHandler = holder.clickHandler(slot);
  703:         if (clickHandler == null || clickHandler.action() == null) {
  704:             handleRuntimeWorldItemClick(player, event, holder);
  705:             return;
  706:         }
  707: 
  708:         GuiTrigger trigger = clickHandler.action().resolve(event.isLeftClick(), event.isRightClick());
  709:         if (trigger == null) {
  710:             return;
  711:         }
  712: 
  713:         PlayerGuiSession session = playerSessions.getOrCreate(player.getUniqueId());
  714:         triggerDispatcher.execute(player, session, trigger, clickHandler.extra(), holder.guiId() + ":" + slot);
  715:     }
```

## src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java, Zeilen 2672–2706

```java
 2672:     public void handleInventoryClick(InventoryClickEvent event) {
 2673:         if (!(event.getWhoClicked() instanceof Player player)) {
 2674:             return;
 2675:         }
 2676: 
 2677:         Inventory top = event.getView().getTopInventory();
 2678:         Inventory clicked = event.getClickedInventory();
 2679:         if (clicked == null || clicked.getType() == InventoryType.PLAYER || clicked != top) {
 2680:             return;
 2681:         }
 2682: 
 2683:         InventoryHolder holder = top.getHolder();
 2684:         if (holder instanceof MainHolder mainHolder) {
 2685:             event.setCancelled(true);
 2686:             handleMainMenuClick(player, event, mainHolder);
 2687:             return;
 2688:         }
 2689: 
 2690:         if (holder instanceof CreateOptionsHolder createOptionsHolder) {
 2691:             event.setCancelled(true);
 2692:             handleCreateOptionsClick(player, event, createOptionsHolder);
 2693:             return;
 2694:         }
 2695: 
 2696:         if (holder instanceof IconSelectorHolder selectorHolder) {
 2697:             event.setCancelled(true);
 2698:             handleIconSelectorClick(player, event, selectorHolder);
 2699:             return;
 2700:         }
 2701: 
 2702:         if (holder instanceof RuntimeGuiHolder runtimeHolder) {
 2703:             event.setCancelled(true);
 2704:             handleRuntimeGuiClick(player, event, runtimeHolder);
 2705:         }
 2706:     }
```

## src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java, Zeilen 2033–2074

```java
 2033:     public void executeNavCreateWizard(Player player, String worldTypeArg, String serverIdArg) {
 2034:         if (!hasPermission(player, Permissions.USE, true) || !hasPermission(player, Permissions.NAV_MY_WORLD_CREATE, true)) {
 2035:             return;
 2036:         }
 2037: 
 2038:         String normalizedType = worldTypeArg == null ? "" : worldTypeArg.trim().toUpperCase(Locale.ROOT);
 2039:         boolean voidWorld;
 2040:         if ("VOID".equals(normalizedType)) {
 2041:             voidWorld = true;
 2042:         } else if ("FLAT".equals(normalizedType)) {
 2043:             voidWorld = false;
 2044:         } else {
 2045:             send(player, "create.invalid-world-type");
 2046:             return;
 2047:         }
 2048: 
 2049:         String normalizedServer = serverIdArg == null ? "" : serverIdArg.trim();
 2050:         if (normalizedServer.isBlank()) {
 2051:             send(player, "create.server-missing");
 2052:             return;
 2053:         }
 2054: 
 2055:         if (isBlockedServerName(normalizedServer)) {
 2056:             send(player, "create.server-blocked", "%server%", normalizedServer);
 2057:             return;
 2058:         }
 2059: 
 2060:         List<String> selectableServers = resolveSelectableServerNames();
 2061:         if (!selectableServers.isEmpty() && selectableServers.stream().noneMatch(server -> server.equalsIgnoreCase(normalizedServer))) {
 2062:             send(player, "create.server-unknown", "%server%", normalizedServer);
 2063:             return;
 2064:         }
 2065: 
 2066:         if (voidWorld && Bukkit.getPluginManager().getPlugin("VoidGen") == null) {
 2067:             send(player, "create.voidgen-missing");
 2068:             return;
 2069:         }
 2070: 
 2071:         String worldName = generateLocalWorldName(player);
 2072: 
 2073:         createWorld(player, worldName, null, false, "private", null, List.of(), voidWorld, false, true, normalizedServer);
 2074:     }
```

## src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java, Zeilen 3318–3537

```java
 3318:     private void createWorld(Player player) {
 3319:         String worldName = generateLocalWorldName(player);
 3320:         createWorld(player, worldName, null, false, "private", null, List.of(), false, false, true);
 3321:     }
 3322: 
 3323:     private void createWorld(Player player, String worldName, String orderLabel) {
 3324:         createWorld(player, worldName, orderLabel, false, orderLabel == null ? "private" : "ticket", orderLabel, List.of(), false, false, true);
 3325:     }
 3326: 
 3327:     private void createWorld(
 3328:         Player player,
 3329:         String worldName,
 3330:         String orderLabel,
 3331:         boolean isPublic,
 3332:         String sourceType,
 3333:         String ticketOrderId,
 3334:         List<String> customers
 3335:     ) {
 3336:         createWorld(player, worldName, orderLabel, isPublic, sourceType, ticketOrderId, customers, false, false, true);
 3337:     }
 3338: 
 3339:     private void createWorld(
 3340:         Player player,
 3341:         String worldName,
 3342:         String orderLabel,
 3343:         boolean isPublic,
 3344:         String sourceType,
 3345:         String ticketOrderId,
 3346:         List<String> customers,
 3347:         boolean voidWorld,
 3348:         boolean chunkyEnabled,
 3349:         boolean notifyWhenVisible
 3350:     ) {
 3351:         createWorld(player, worldName, orderLabel, isPublic, sourceType, ticketOrderId, customers, voidWorld, chunkyEnabled, notifyWhenVisible, null);
 3352:     }
 3353: 
 3354:     /**
 3355:      * @param requestedServer explizit gewünschter Ziel-Server (z.B. aus dem guis.yml create-world/select-server
 3356:      *                        Wizard); wenn null/leer, wird der Ziel-Server wie bisher automatisch bestimmt.
 3357:      */
 3358:     private void createWorld(
 3359:         Player player,
 3360:         String worldName,
 3361:         String orderLabel,
 3362:         boolean isPublic,
 3363:         String sourceType,
 3364:         String ticketOrderId,
 3365:         List<String> customers,
 3366:         boolean voidWorld,
 3367:         boolean chunkyEnabled,
 3368:         boolean notifyWhenVisible,
 3369:         String requestedServer
 3370:     ) {
 3371:         int nextIndex = resolveMetadataWorldIndex(player, worldName);
 3372:         String targetServer = requestedServer != null && !requestedServer.isBlank()
 3373:             ? requestedServer.trim()
 3374:             : resolveTargetCreationServer();
 3375:         if (isBlockedServerName(targetServer)) {
 3376:             send(player, "create.blocked-server", "%server%", targetServer);
 3377:             return;
 3378:         }
 3379: 
 3380:         String localServer = resolveLocalServerId();
 3381:         boolean shouldCreateLocally = targetServer.isBlank()
 3382:             || localServer.isBlank()
 3383:             || isSameServerIdentifier(targetServer, localServer);
 3384: 
 3385:         if (!shouldCreateLocally) {
 3386:             send(player, "create.remote-info-1");
 3387:             send(player, "create.remote-info-2", "%server%", targetServer);
 3388:             String creatingTitle = configuredMessageLine("create-creating-title", "Welt wird erstellt...");
 3389:             player.sendTitle(ChatColor.translateAlternateColorCodes('&', creatingTitle), "", 10, 70, 10);
 3390: 
 3391:             persistWorldMetadataWithRetry(
 3392:                 player.getUniqueId(),
 3393:                 player.getName(),
 3394:                 worldName,
 3395:                 orderLabel,
 3396:                 ticketOrderId,
 3397:                 sourceType,
 3398:                 customers,
 3399:                 nextIndex,
 3400:                 isPublic,
 3401:                 3,
 3402:                 false,
 3403:                 notifyWhenVisible,
 3404:                 targetServer
 3405:             );
 3406: 
 3407:             if (notifyWhenVisible) {
 3408:                 send(player, "create.dashboard-visible-connect");
 3409:             }
 3410:             return;
 3411:         }
 3412: 
 3413:         WorldCreator creator = buildWorldCreator(worldName, voidWorld);
 3414: 
 3415:         World created = Bukkit.createWorld(creator);
 3416:         if (created == null) {
 3417:             send(player, "create-failed", "%world%", worldName);
 3418:             return;
 3419:         }
 3420: 
 3421:         applyWorldGuardProtection(created, player.getUniqueId().toString(), player.getName(), List.of(), List.of());
 3422:         runPostCreateCommands(created);
 3423: 
 3424:         ConsoleCommandSender console = Bukkit.getConsoleSender();
 3425:         Bukkit.dispatchCommand(console, "mv import " + worldName + " normal");
 3426: 
 3427:         // Title sofort zeigen
 3428:         String creatingTitle = configuredMessageLine("create-creating-title", "Welt wird erstellt...");
 3429:         player.sendTitle(ChatColor.translateAlternateColorCodes('&', creatingTitle), "", 10, 70, 10);
 3430: 
 3431:         new BukkitRunnable() {
 3432:             @Override
 3433:             public void run() {
 3434:                 World world = Bukkit.getWorld(worldName);
 3435:                 if (world == null) {
 3436:                     send(player, "create-failed", "%world%", worldName);
 3437:                     return;
 3438:                 }
 3439: 
 3440:                 applyWorldGuardProtection(world, player.getUniqueId().toString(), player.getName(), List.of(), List.of());
 3441:                 runPostCreateCommands(world);
 3442: 
 3443:                 if (voidWorld) {
 3444:                     prepareVoidSpawn(world);
 3445:                 }
 3446: 
 3447:                 runEntityCleanup(world);
 3448:                 if (chunkyEnabled) {
 3449:                     configureChunkyWorld(world, player);
 3450:                 }
 3451: 
 3452:                 applyWorldGuardProtection(world, player.getUniqueId().toString(), player.getName(), List.of(), List.of());
 3453: 
 3454:                 persistWorldMetadataWithRetry(
 3455:                     player.getUniqueId(),
 3456:                     player.getName(),
 3457:                     worldName,
 3458:                     orderLabel,
 3459:                     ticketOrderId,
 3460:                     sourceType,
 3461:                     customers,
 3462:                     nextIndex,
 3463:                     isPublic,
 3464:                     3,
 3465:                     false,
 3466:                     notifyWhenVisible,
 3467:                     targetServer
 3468:                 );
 3469: 
 3470:                 send(player, "create-success", "%world%", worldName);
 3471:                 if (orderLabel != null) {
 3472:                     send(player, "create.order-linked", "%order%", orderLabel);
 3473:                 }
 3474:                 if (voidWorld) {
 3475:                     send(player, "create.template-void");
 3476:                 }
 3477:                 if (chunkyEnabled) {
 3478:                     send(player, "create.chunky-started");
 3479:                 }
 3480:                 if (notifyWhenVisible) {
 3481:                     send(player, "create.dashboard-visible-teleport");
 3482:                     openGui(player, "my-worlds");
 3483:                     return;
 3484:                 }
 3485: 
 3486:                 scheduleCreatedWorldJoin(player, worldName, targetServer, 100L, false);
 3487:             }
 3488:         }.runTaskLater(plugin, 20L);
 3489:     }
 3490: 
 3491:     private String generateLocalWorldName(Player player) {
 3492:         for (int attempt = 0; attempt < 200; attempt++) {
 3493:             int localId = ThreadLocalRandom.current().nextInt(1000, 10_000);
 3494:             String worldName = player.getName() + "-" + localId;
 3495:             if (isLocalWorldNameAvailable(worldName)) {
 3496:                 return worldName;
 3497:             }
 3498:         }
 3499: 
 3500:         int fallbackId = (int) ((System.currentTimeMillis() % 9000L) + 1000L);
 3501:         return player.getName() + "-" + fallbackId;
 3502:     }
 3503: 
 3504:     private boolean isLocalWorldNameAvailable(String worldName) {
 3505:         if (worldName == null || worldName.isBlank()) {
 3506:             return false;
 3507:         }
 3508:         if (Bukkit.getWorld(worldName) != null) {
 3509:             return false;
 3510:         }
 3511:         File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
 3512:         return !worldFolder.exists();
 3513:     }
 3514: 
 3515:     private int resolveMetadataWorldIndex(Player player, String worldName) {
 3516:         Matcher matcher = GENERATED_WORLD_NAME_PATTERN.matcher(worldName == null ? "" : worldName);
 3517:         if (matcher.matches()) {
 3518:             return Integer.parseInt(matcher.group(1));
 3519:         }
 3520:         return repository.nextWorldIndex(player.getUniqueId().toString());
 3521:     }
 3522: 
 3523:     private WorldCreator buildWorldCreator(String worldName, boolean voidWorld) {
 3524:         WorldCreator creator = new WorldCreator(worldName)
 3525:             .environment(World.Environment.NORMAL)
 3526:             .generateStructures(false);
 3527: 
 3528:         if (voidWorld) {
 3529:             creator.type(WorldType.NORMAL).generator("VoidGen");
 3530:             return creator;
 3531:         }
 3532: 
 3533:         creator
 3534:             .type(WorldType.FLAT)
 3535:             .generatorSettings(FLAT_WORLD_GENERATOR_SETTINGS);
 3536:         return creator;
 3537:     }
```

## src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java, Zeilen 956–1065

```java
  956:     public void syncOpenTicketWorlds() {
  957:         Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
  958:             Map<String, WorldEntry> worldsByName = new LinkedHashMap<>();
  959:             for (WorldEntry entry : repository.listOpenTicketWorlds()) {
  960:                 worldsByName.put(entry.worldName().toLowerCase(Locale.ROOT), entry);
  961:             }
  962: 
  963:             String localServerId = resolveLocalServerId();
  964:             if (localServerId != null && !localServerId.isBlank()) {
  965:                 for (WorldEntry entry : repository.listServerWorlds(localServerId)) {
  966:                     worldsByName.put(entry.worldName().toLowerCase(Locale.ROOT), entry);
  967:                 }
  968:             }
  969: 
  970:             if (worldsByName.isEmpty()) {
  971:                 return;
  972:             }
  973: 
  974:             Bukkit.getScheduler().runTask(plugin, () -> {
  975:                 for (WorldEntry entry : worldsByName.values()) {
  976:                     if (!isWorldOnThisServer(entry)) {
  977:                         continue;
  978:                     }
  979:                     if (Bukkit.getWorld(entry.worldName()) != null) {
  980:                         continue;
  981:                     }
  982:                     createWorldFromMetadata(entry);
  983:                 }
  984:             });
  985:         });
  986:     }
  987: 
  988:     private void createWorldFromMetadata(WorldEntry entry) {
  989:         String worldName = entry.worldName();
  990:         String worldKey = worldName.toLowerCase(Locale.ROOT);
  991:         String ownerUuid = entry.ownerUuid();
  992:         String ownerName = entry.ownerName();
  993:         List<String> customers = entry.customers() == null || entry.customers().isEmpty()
  994:             ? repository.getOrderSummary(entry.ticketOrderId())
  995:                 .map(summary -> summary.customerName() == null || summary.customerName().isBlank()
  996:                     ? List.<String>of()
  997:                     : List.of(summary.customerName().trim()))
  998:                 .orElse(List.of())
  999:             : entry.customers();
 1000:         int worldIndex = repository.nextWorldIndex(ownerUuid);
 1001: 
 1002:         WorldCreator creator = buildWorldCreator(worldName, false);
 1003: 
 1004:         World created = Bukkit.createWorld(creator);
 1005:         if (created == null) {
 1006:             metadataWorldCreationsInProgress.remove(worldKey);
 1007:             plugin.getLogger().warning("Ticket-Welt konnte nicht erstellt werden: " + worldName);
 1008:             return;
 1009:         }
 1010: 
 1011:         ConsoleCommandSender console = Bukkit.getConsoleSender();
 1012:         Bukkit.dispatchCommand(console, "mv import " + worldName + " normal");
 1013: 
 1014:         new BukkitRunnable() {
 1015:             @Override
 1016:             public void run() {
 1017:                 World world = Bukkit.getWorld(worldName);
 1018:                 if (world == null) {
 1019:                     metadataWorldCreationsInProgress.remove(worldKey);
 1020:                     plugin.getLogger().warning("Ticket-Welt konnte nach dem Import nicht geladen werden: " + worldName);
 1021:                     return;
 1022:                 }
 1023: 
 1024:                 applyWorldGuardProtection(world, ownerUuid, ownerName, List.of(), List.of());
 1025:                 runPostCreateCommands(world);
 1026: 
 1027:                 runEntityCleanup(world);
 1028: 
 1029:                 persistWorldMetadataWithRetry(
 1030:                     UUID.fromString(ownerUuid),
 1031:                     ownerName,
 1032:                     worldName,
 1033:                     entry.orderLabel(),
 1034:                     entry.ticketOrderId(),
 1035:                     entry.sourceType(),
 1036:                     customers,
 1037:                     worldIndex,
 1038:                     entry.isPublic(),
 1039:                     3,
 1040:                     false,
 1041:                     false,
 1042:                     entry.serverName()
 1043:                 );
 1044:                 metadataWorldCreationsInProgress.remove(worldKey);
 1045:             }
 1046:         }.runTaskLater(plugin, 20L);
 1047:     }
 1048: 
 1049:     private void ensureWorldCreatedFromMetadata(WorldEntry entry) {
 1050:         if (entry == null || !isWorldOnThisServer(entry)) {
 1051:             return;
 1052:         }
 1053: 
 1054:         String worldName = entry.worldName();
 1055:         if (worldName == null || worldName.isBlank() || Bukkit.getWorld(worldName) != null) {
 1056:             return;
 1057:         }
 1058: 
 1059:         String worldKey = worldName.toLowerCase(Locale.ROOT);
 1060:         if (!metadataWorldCreationsInProgress.add(worldKey)) {
 1061:             return;
 1062:         }
 1063: 
 1064:         createWorldFromMetadata(entry);
 1065:     }
```

## src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java, Zeilen 3660–3759

```java
 3660:     private void persistWorldMetadataWithRetry(
 3661:         UUID playerId,
 3662:         String ownerName,
 3663:         String worldName,
 3664:         String orderLabel,
 3665:         String ticketOrderId,
 3666:         String sourceType,
 3667:         List<String> customers,
 3668:         int worldIndex,
 3669:         boolean isPublic,
 3670:         int retriesLeft,
 3671:         boolean wasRetry,
 3672:         boolean notifyWhenVisible,
 3673:         String serverNameOverride
 3674:     ) {
 3675:         Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 3676:             boolean persisted = repository.insertWorld(
 3677:                 worldName,
 3678:                 playerId.toString(),
 3679:                 ownerName,
 3680:                 orderLabel,
 3681:                 ticketOrderId,
 3682:                 sourceType,
 3683:                 null,
 3684:                 customers,
 3685:                 worldIndex,
 3686:                 worldName,
 3687:                 Material.GRASS_BLOCK.name(),
 3688:                 isPublic,
 3689:                 false,
 3690:                 serverNameOverride
 3691:             );
 3692: 
 3693:             if (persisted) {
 3694:                 if (wasRetry || notifyWhenVisible) {
 3695:                     Bukkit.getScheduler().runTask(plugin, () -> {
 3696:                         Player online = Bukkit.getPlayer(playerId);
 3697:                         if (online != null && online.isOnline()) {
 3698:                             if (notifyWhenVisible) {
 3699:                                 send(online, "transfer.dashboard-visible", "%world%", worldName);
 3700:                                 scheduleCreatedWorldJoin(online, worldName, serverNameOverride, 100L, true);
 3701:                             }
 3702:                             if (wasRetry) {
 3703:                                 send(online, "transfer.dashboard-resynced");
 3704:                             }
 3705:                         }
 3706:                     });
 3707:                 }
 3708:                 return;
 3709:             }
 3710: 
 3711:             if (retriesLeft <= 0) {
 3712:                 Bukkit.getScheduler().runTask(plugin, () -> {
 3713:                     Player online = Bukkit.getPlayer(playerId);
 3714:                     if (online != null && online.isOnline()) {
 3715:                         send(online, "transfer.api-unreachable");
 3716:                     }
 3717:                 });
 3718:                 return;
 3719:             }
 3720: 
 3721:             Bukkit.getScheduler().runTaskLater(plugin, () ->
 3722:                 persistWorldMetadataWithRetry(
 3723:                     playerId,
 3724:                     ownerName,
 3725:                     worldName,
 3726:                     orderLabel,
 3727:                     ticketOrderId,
 3728:                     sourceType,
 3729:                     customers,
 3730:                     worldIndex,
 3731:                     isPublic,
 3732:                     retriesLeft - 1,
 3733:                     true,
 3734:                     notifyWhenVisible,
 3735:                     serverNameOverride
 3736:                 ),
 3737:                 20L * 5
 3738:             );
 3739:         });
 3740:     }
 3741: 
 3742:     private void runEntityCleanup(World world) {
 3743:         new BukkitRunnable() {
 3744:             int passes = 0;
 3745: 
 3746:             @Override
 3747:             public void run() {
 3748:                 for (Entity entity : world.getEntities()) {
 3749:                     if (entity instanceof Player) {
 3750:                         continue;
 3751:                     }
 3752:                     entity.remove();
 3753:                 }
 3754:                 passes++;
 3755:                 if (passes >= 3) {
 3756:                     cancel();
 3757:                 }
 3758:             }
 3759:         }.runTaskTimer(plugin, 20L, 20L);
```

## src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java, Zeilen 4943–4987

```java
 4943:     private void runPostCreateCommands(World world) {
 4944:         if (world == null) {
 4945:             return;
 4946:         }
 4947: 
 4948:         applyConfiguredWorldGameRules(world);
 4949:         applyConfiguredWorldGuardFlags(world);
 4950: 
 4951:         // Optional: legacy/custom free-form commands after structured sections.
 4952:         List<String> commands = worldDefaultsConfig.getStringList("world-defaults.post-create-commands");
 4953:         if (commands == null || commands.isEmpty()) {
 4954:             return;
 4955:         }
 4956: 
 4957:         String worldName = world.getName();
 4958:         String worldLower = worldName.toLowerCase(Locale.ROOT);
 4959:         for (String raw : commands) {
 4960:             if (raw == null || raw.isBlank()) {
 4961:                 continue;
 4962:             }
 4963: 
 4964:             String resolved = raw
 4965:                 .replace("%world%", worldName)
 4966:                 .replace("%world_lower%", worldLower)
 4967:                 .replace("%region%", worldName)
 4968:                 .replace("%region_lower%", worldLower)
 4969:                 .trim();
 4970: 
 4971:             if (resolved.startsWith("/")) {
 4972:                 resolved = resolved.substring(1);
 4973:             }
 4974:             if (resolved.isBlank()) {
 4975:                 continue;
 4976:             }
 4977: 
 4978:             if (tryApplyGameRuleEntry(world, resolved)) {
 4979:                 continue;
 4980:             }
 4981: 
 4982:             boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
 4983:             if (!ok) {
 4984:                 plugin.getLogger().warning("Post-Create-Command fehlgeschlagen: " + resolved);
 4985:             }
 4986:         }
 4987:     }
```
