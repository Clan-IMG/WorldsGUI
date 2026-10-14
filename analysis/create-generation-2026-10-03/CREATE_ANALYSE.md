# WorldsGUI CREATE-Pfad: erneute Analyse

Analysierter Stand vor Diagnose-Logs: Commit b231e0d, 03.10.2026. Neuer Befund: Auch eine neu erstellte Flatworld zeigt steinartige Höhen und Chunkkanten. Das sichtbare Terrain wird als Nutzerbeobachtung zugrunde gelegt; ein konkreter NBT-Befund für diese neue Welt liegt noch nicht vor. Der alte Restore-/Sync-Bug erklärt diesen Fall nicht hinreichend.

## Ergebnis und Einstufung

**BEWIESEN:** Die beiden tatsächlichen Bukkit-Create-Aufrufe liegen in GuiManager.createWorld() und createWorldFromMetadata(). Für die gewählte FLAT-Neuanlage erhalten beide den FLAT-Creator bereits vor dem Aufruf. Kein WorldsGUI-Pfad setzt FLAT erst danach. Es gibt kein mv create, keine Multiverse-Java-API und keinen eigenen ChunkGenerator/BiomeProvider im Plugin.

**BEWIESEN:** Der lokale GUI-Create ist synchron auf dem Bukkit-Hauptthread. Der Sync sammelt API-Daten asynchron, führt seine Create-Aktion aber ebenfalls erst auf dem Hauptthread aus. Er kann den synchronen Create nicht regulär parallel unterbrechen. Nach dem Create überspringt er die bereits geladene Welt. Der Restore-Race bleibt real, weil dort zwischen getrennten Hauptthreadaktionen lange Worker-Phasen liegen; diese Begründung darf nicht auf eine normale lokale Neuanlage übertragen werden.

**BEWIESEN:** Bei Remote-Neuanlage entstehen die Dateien erst auf dem Zielserver durch createWorldFromMetadata(). Die Wahl VOID/FLAT wird nicht persistiert, und dort ist false/FLAT fest verdrahtet. Dies ist ein gesonderter Fehler für Remote-VOID, aber keine belegte Erklärung für NOISE bei gewähltem FLAT.

**MÖGLICH:** Der erwartete Creator ist korrekt, aber Paper verwendet eine schon geladene Welt, vorhandene level.dat oder eine weltbezogene Bukkit-Custom-Generatorzuordnung. Auch ein abweichender Leaf-/Plugin-Build oder ein weiteres Plugin kommt infrage. Keine dieser Ursachen ist auf dem betroffenen Server derzeit bewiesen.

**Keine ausreichend belegte SEHR-WAHRSCHEINLICH-Hypothese für die steinartige Generierung einer echten frischen FLAT-Neuanlage.** Eine Einstufung als sehr wahrscheinlich wäre ohne Laufzeit-/NBT-Belege spekulativ. Die Diagnoseinstrumentierung muss die offenen Alternativen unterscheiden.

## 1. Vom GUI-Klick zum tatsächlichen Aufruf

Die repository-eigene guis.yml definiert:

- create-world → Flatworld-Slot: SELECT world-type=FLAT.
- select-server → Server-Slot: SELECT server=<server_id>.
- confirm-command: nav create %selection:world-type% %selection:server%.
- confirm → Bestätigen: COMMAND %confirm-command%.

Konkrete Folge:

```text
InventoryClickEvent
→ InventoryListener.onInventoryClick()
→ GuiManager.handleInventoryClick()
→ RuntimeGuiHolder-Zweig → handleRuntimeGuiClick()
→ TriggerDispatcher.execute()
→ executeSelect(): Session-Auswahl FLAT speichern, GUI-Params expandieren
→ Bestätigung: executeCommand() + PlaceholderExpander.expand()
→ CommandDispatcher-Lambda im GuiManager-Konstruktor
→ dispatchRuntimeGuiCommand() passt nur für Delete-Confirm, liefert hier false
→ Bukkit.dispatchCommand(player, "nav create FLAT <server-id>")
→ NavCommand.onCommand(): case create, args.length>=3
→ GuiManager.executeNavCreateWizard(player, args[1], args[2])
→ normalizedType=FLAT → voidWorld=false
→ generateLocalWorldName(player)
→ createWorld(..., voidWorld=false, chunkyEnabled=false, requestedServer)
```

Das Repository enthält lediglich die ausgelieferte GUI-Konfiguration. Eine anders konfigurierte plugins/WorldsGUI/guis.yml auf dem Live-Server kann einen anderen Command erzeugen. Dafür werden die tatsächlich expandierten Create-/MV-Commands mitgeloggt.

Der Wizard erzeugt automatisch `<Spieler>-<1000..9999>`. Er übernimmt keinen manuell eingegebenen Namen GeneratorTest123. `/nav my-world create GeneratorTest123` geht hingegen über executeNavMyWorldCreate() und die Create-Overloads. Ticket- und Legacy-CreateOptions-Pfade enden ebenfalls im selben abschließenden createWorld().

## 2. Lokal versus Remote

Der abschließende createWorld()-Overload ermittelt targetServer und localServer. Bei leerer oder als gleich erkannter Server-ID wird lokal erstellt. Sonst wird nur der API-Eintrag geschrieben und zurückgekehrt.

### Lokal

```text
buildWorldCreator(worldName, false)
→ vollständiger Creator zurück
→ Bukkit.createWorld(creator)
→ WorldGuard-Schutz
→ runPostCreateCommands(created)
→ mv import <worldName> normal
→ nach 20 Ticks: World aus Bukkit holen
→ WorldGuard + Post-Create-Commands erneut
→ runEntityCleanup()
→ optional Chunky [im neuen Wizard false]
→ API-Metadaten persistieren
→ späterer Join: nur bei fehlender geladener Welt mv load
```

Es gibt in diesem Ablauf keinen expliziten Unload. Post-Create-Commands sind im Repository leer. Die frei konfigurierbaren Commands auf dem Live-Server müssen separat geprüft werden, weil sie zweimal aufgerufen werden. Der Code kann darüber beliebige Konsolencommands ausführen. EntityCleanup entfernt Entities, verändert keine Generatorsettings. prepareVoidSpawn verändert nur zwei Blöcke bei VOID, läuft nicht im FLAT-Wizard.

### Remote

```text
GUI-Server: createWorld() → persistWorldMetadataWithRetry() → repository.insertWorld()
→ Zielserver: 2s-Sync → listServerWorlds()
→ createWorldFromMetadata(entry)
→ buildWorldCreator(worldName, false)
→ Bukkit.createWorld(creator)
→ mv import <worldName> normal
→ nach 20 Ticks Defaults/Schutz/Cleanup/API
```

Remote kann der Zielserver-Sync die Welt bereits erstellen, während der ursprüngliche GUI-Vorgang auf dem anderen Server noch Rückmeldungen/Transfers bearbeitet. Dies ist der tatsächliche Erstellungsweg, kein zweiter NORMAL-Create desselben Plugins.

Wichtig: generateLocalWorldName() prüft die Namensverfügbarkeit auf dem GUI-Server, nicht im Dateisystem des Zielservers. **BEWIESEN:** Eine Remote-Namenskollision wird dort nicht vorab ausgeschlossen. **MÖGLICH:** Ein auf dem Zielserver vorhandener Ordner oder eine geladene Welt wird als vermeintlich neue Welt wiederverwendet. Der Fallback nach 200 erfolglosen Namensversuchen wird zudem nicht erneut auf Verfügbarkeit geprüft.

## 3. Exakter Creator unmittelbar vor Bukkit.createWorld()

| Eigenschaft | Local FLAT und Metadata-Sync | Local VOID |
|---|---|---|
| name | tatsächlich generierter/übergebener Weltname | genauso |
| environment | World.Environment.NORMAL | NORMAL |
| type | WorldType.FLAT | WorldType.NORMAL |
| generator | null im Creator | von generator("VoidGen") aufgelöstes Plugin-Objekt, sofern erfolgreich |
| generatorSettings | unten stehendes vollständiges JSON | leerer WorldCreator-Default |
| biomeProvider | null im Creator | null im Creator |
| seed | vom WorldCreator-Konstruktor gewählter zufälliger long; WorldsGUI setzt keinen Seed | genauso |
| generateStructures | false | false |

FLAT-JSON:

```json
{"biome":"minecraft:plains","features":false,"lakes":false,"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":64},{"block":"minecraft:grass_block","height":1}]}
```

Diese 66 Schichten enthalten keinen Stein. Der Code allein erklärt damit keine massiv hochgezogenen Steinformationen. Die genaue Settings-Anwendung des tatsächlich eingesetzten Leaf-Builds muss trotzdem beobachtet werden.

Die Creator-Defaults einschließlich zufälligem Seed sind im [Paper-WorldCreator](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-api/src/main/java/org/bukkit/WorldCreator.java) nachprüfbar. Die konkrete Seed-Zahl kann statisch nicht bestimmt werden und wird im Debug unmittelbar vor Create ausgegeben. Die beiden Branches FLAT und VOID mischen sich im lokalen Builder nicht.

## 4. Was Paper aus diesem Aufruf macht

Die folgenden Aussagen betreffen den geprüften [Paper-1.21.11-CraftServer](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java#L1093), gegen dessen API das Projekt kompiliert. Der konkrete Live-Leaf-Build ist noch unbekannt.

1. Paper sucht bereits geladene Welten nach Name und Key. Ein identischer Treffer wird zurückgegeben: Die Creator-Settings werden dann nicht neu angewendet.
2. Bei null im Creator ermittelt Paper einen ChunkGenerator und BiomeProvider aus weltbezogenen Bukkit-Konfigurationszuordnungen. null im Creator beweist daher keine Vanilla-Laufzeitgenerierung.
3. Vorhandene Weltdaten bestimmen die Dimensionen; andernfalls werden type, generatorSettings und Seed für die Neuanlage verwendet.
4. Paper baut die ServerLevel aus dem gewählten Dimension-Generator sowie optionalem Bukkit-Generator/BiomeProvider auf und bereitet die Welt vor.

Es gibt somit keinen Beweis, dass die gezeigten korrekten Creator-Eingaben auf dem Live-Server auch als tatsächlicher Laufzeitgenerator ankommen. World#getGenerator() benennt den Bukkit-Custom-Generator, nicht den Vanilla-NMS-Generator. World#getWorldType() ist ein weiterer Indikator, kein Ersatz für den exakten NBT-Generatorpfad.

WorldsGUI erzwingt direkt nach Neuanlage kein World.save(). Wann genau die erste level.dat geschrieben wird, ist daher nicht allein aus dem Plugin bewiesen. Fehlende level.dat unmittelbar nach dem Create ist separat von falscher Generierung zu unterscheiden. Die Diagnose liest Dateistatus und NBT ohne Save; für einen anschließenden Offline-/Shell-Befund kann auf dem Testserver bewusst `save-all flush` verwendet werden.

## 5. Multiverse: exakter Befehl und Bedeutung

WorldsGUI erzeugt kein mv create und benutzt keine Multiverse-Java-API. Beide Create-Pfade führen nach Bukkit.createWorld() genau Folgendes aus:

```text
mv import <tatsächlicher Weltname> normal
```

Es wird weder ein WorldType-Parameter FLAT noch ein -g Custom-Generator übergeben. normal bezeichnet das Environment, nicht WorldType.NORMAL.

[Multiverse 5.8.1 WorldManager](https://github.com/Multiverse/Multiverse-Core/blob/5.8.1/src/main/java/org/mvplugins/multiverse/core/world/WorldManager.java#L311) kann eine nur in Bukkit geladene Welt importieren; dabei übernimmt es die vorhandene Welt und registriert Import-Optionen. Es ersetzt deren aktiven ChunkGenerator nicht. Eine bereits in MV registrierte Welt führt dagegen zur Already-exists-Antwort. Der Import registriert den Generatorstring aus Import-/Bukkit-Konfiguration, nicht automatisch die im Bukkit-Creator gewählte VoidGen-ID. Das ist ein bewiesenes Persistenzrisiko für Custom-Generatoren, aber keine belegte spontane Änderung einer gerade geladenen Vanilla-FLAT-Welt zu NOISE.

Daraus folgt: Bukkit → Import ist für eine neue Vanilla-Welt nicht allein der gesuchte Beweis für falsche Chunkgenerierung. Ein späterer Reload mit fehlerhaften/fehlenden Metadaten ist eine gesonderte Hypothese und muss im Log tatsächlich auftauchen.

## 6. Zweite Create-/Load-Pfade und Sync

- GuiManager.createWorld() → Bukkit.createWorld(): lokaler GUI-/Command-/Ticket-/Legacy-Pfad.
- GuiManager.createWorldFromMetadata() → Bukkit.createWorld(): 2s-Sync und Pending-Transfer-Erstellung.
- joinCreatedWorldWhenReady(), Auto-Create-OnJoin-Callback, joinWorldLocally(), resolveNextOwnWorld(), Clone-Callback und BackupHooks.loadWorld(): mv load nur wenn Bukkit.getWorld fehlt.
- executeNavMyWorldImport(): mv import wenn Welt nicht bereits geladen ist.
- createWorld()/createWorldFromMetadata(): mv import unmittelbar nach Create.
- BackupHooks.unloadWorldForRestore(): mv unload; kein Teil des normalen Create-Flows.
- Frei konfigurierte Post-Create-Commands und Runtime-GUI-Commands können weitere MV-Aufrufe enthalten.

Die Metadaten-Sperre wird vom 2s-Sync nicht benutzt. Das ist mangelnde Koordinierung, beweist aber keine gleichzeitige Ausführung zweier Create-Calls auf dem Hauptthread. Auch mehrere bereits eingereihte Sync-Runnables prüfen Bukkit.getWorld erst bei ihrer tatsächlichen Ausführung. Nach erfolgreichem Create überspringen sie die Welt.

**MÖGLICH:** Andere Plugins reagieren synchron innerhalb von WorldInitEvent/WorldLoadEvent und können reentrant handeln. WorldsGUI hat bisher keine solchen Listener. Lesende Diagnose-Listener protokollieren diese Ereignisse mit kurzer Aufrufkette; sie greifen nicht in das Laden oder Generieren ein.

## 7. Was die leere Linux-Ausgabe wirklich aussagt

Der gezeigte Befehl unterdrückt alle gzip-Fehler mit 2>/dev/null. Eine fehlende Datei, falscher Ordner, falscher Serverpfad oder ungültiger gzip-Inhalt kann deshalb wie „keine Generatorsettings“ aussehen. Auch grep ohne Treffer sagt nichts über die Existenz der Welt aus.

Der aktuelle Wizard erzeugt zudem automatisch Spieler-Namen mit Zahl. **MÖGLICH:** GeneratorTest123 war nur ein vermuteter Name. Der Debug-Befund muss erst den tatsächlichen World#getWorldFolder(), NamespacedKey und Weltname liefern. Keinesfalls aus der leeren Ausgabe auf minecraft:noise schließen.

## 8. Diagnoseentscheidung

Die statische Analyse findet keinen bewiesenen Fehler „FLAT erst nach Create“ und keinen WorldsGUI-NORMAL-Create für die gewählte FLAT-Neuanlage. Die konkrete Ursache der neuen Terrainfehler ist offen. Deshalb wird gemäß Nutzerauftrag jetzt ausschließlich temporäre Diagnose ergänzt, kein Terrain-/Restore-/Generatorfix und kein Reparaturpatch.

Die Logs müssen zeigen:

- tatsächlichen GUI-/Wizard-Command, gewählte Weltart, Name und Ziel-/lokalen Server;
- Caller/Phase vor allen direkten Bukkit-Create- und MV-Create/Import/Load/Unload-Aufrufen;
- Creator Environment, WorldType, Generatorklasse, Settings, BiomeProvider, Seed und Strukturen;
- Existenz des Ordners/level.dat vor dem Create und bereits geladene Welt nach Name/Key;
- danach tatsächliche Weltidentität, WorldType, Custom-Generator, BiomeProvider und Originalpfad;
- WorldInit/Load/Unload-Ereignisse einschließlich Aufrufkette, externe Ereignisse nach Plugin-Aktivierung eingeschlossen;
- lesende NBT-Snapshots sofort sowie nach 1, 20 und 100 Ticks, ohne Save, Chunkzugriff oder Dateiveränderung;
- Server-/Plugin-Version und tatsächliche Plugin-Artefaktkennung.

Die NBT-Auswertung ist Diagnose, keine automatische Migration oder Wiederherstellung. Ein Parsingfehler darf die produktive Create-Aktion nicht abbrechen.

## 9. Auswertung des nächsten Tests

| Laufzeitbefund | Schlussfolgerung |
|---|---|
| CREATOR FLAT, folderExists=false, loadedByName=false; AFTER WorldType.NORMAL | Abweichung entsteht im Create-Aufruf bzw. darin ausgelösten Ereignissen/Servercode; genauen Leaf-Build und Konfiguration prüfen |
| CREATOR FLAT; AFTER customGenerator != null | Externer/Bukkit-konfigurierter Generator beteiligt; Creator-null hatte Vanilla nicht garantiert |
| BEFORE loadedByName=true oder vorhandene level.dat | Keine nachweislich frische Neuanlage; bestehender Weltzustand wird verwendet |
| AFTER FLAT; Overworld-NBT später minecraft:noise | Schreiben/Speichern bzw. Neubeladen untersuchen; Reihenfolge der Snapshots und Events entscheidend |
| AFTER FLAT, Custom-Generator null, NBT flat, kein erneutes Laden; neue Chunks dennoch Steinberge | Diagnose auf tatsächlichen Server-/Chunkgenerator bzw. weitere Chunk-/Blockänderungen ausweiten; Plugin-Create-Reihenfolge erklärt es nicht |
| Zweiter CREATE_BEFORE / COMMAND_BEFORE mit mv load derselben Welt | Exakten Caller und zwischenliegenden Unload-/Fehlerzeitpunkt prüfen |
| Nur remote-request, danach METADATA_CREATE auf Zielserver | Tatsächlicher Remote-Erstellungsweg bestätigt; Zielserver-Logs maßgeblich |
| level.dat fehlt/ist kurzzeitig nicht lesbar | Kein Generatornachweis; nach regulärem Save erneut prüfen |

Für den Test nur eine neue Welt über denselben GUI-Weg erstellen. Es sind keine Chunk-Löschungen, Regenerationen oder pauschalen VoidGen-Einstellungen erforderlich.

## 10. Umgesetzte temporäre Diagnose und Prüfung

Nach Abschluss der statischen Analyse wurde ausschließlich die vom Nutzer für diesen Fall angeforderte Protokollierung ergänzt:

- WorldGenerationDebug protokolliert vor den beiden Create-Aufrufen und vor allen relevanten WorldsGUI-MV-Commands. Die vollständigen originalen Aufrufargumente werden unverändert weitergereicht.
- Die Ereignisse WorldInit, WorldLoad, WorldUnload und WorldSave werden beobachtet. Nach WorldSave erfolgt eine weitere lesende Prüfung; das Ereignis allein beweist noch keine abgeschlossene Speicherung.
- LevelDatGenerationProbe liest gezielt Data/WorldGenSettings/dimensions mit Generator-Typ/Settings pro Dimension, Seed und FLAT-Schichten. Dateigröße, Änderungszeit und Existenz werden zusätzlich geloggt.
- START enthält Serverversion, Plugin-Artefakt und Hash zur Kontrolle des tatsächlich geladenen Builds. SOURCE_BEFORE_DEBUG.md dokumentiert den vollständigen relevanten ursprünglichen Code, GuiManager.before-debug.java.txt den vollständigen ursprünglichen GuiManager.

Validierung: `build-local.ps1` erfolgreich; 13 eigenständige NBT-Prüfungen erfolgreich (FLAT, NOISE, Dimensionstrennung, Schichten, Seed, unveränderte Dateien und fehlerhafte/begrenzte Eingaben). Nach Entfernen der Instrumentierung entspricht GuiManager exakt dem ursprünglichen Snapshot. Das Projekt enthält keine regulären Test-Sourcen; ein Live-Test auf dem betroffenen Leaf-Server ist noch ausstehend.

Testanleitung: [NEXT_TEST.md](NEXT_TEST.md). Es wurde kein Generator-, Restore- oder Terrain-Reparaturpatch angewandt. Die Diagnose kann Timing durch zusätzliche Leseoperationen beeinflussen und wird nach dem Test deaktiviert.
