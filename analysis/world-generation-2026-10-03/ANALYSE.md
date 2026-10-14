# WorldsGUI: Ursachenanalyse der Weltgenerierung

Stand: 03.10.2026. Untersucht: WorldsGUI, Commit `1dfc2b1`, sowie das lokale Schwesterprojekt `worldsgui-api`. Der Sourcecode blieb unverändert. Dieser Bericht unterscheidet einen nachgewiesenen Programmfehler von der noch nicht abschließend belegten Ursache des konkreten Vorfalls.

## Ergebnis

**BEWIESEN: Die automatische Synchronisierung kann eine für den Restore entladene Welt erneut öffnen.** Der Restore setzt zwar eine Sperre, aber `syncOpenTicketWorlds()` und `createWorldFromMetadata()` beachten sie nicht. Der Sync läuft alle zwei Sekunden und berücksichtigt neben offenen Ticketwelten auch die privaten Welten des lokalen Servers. Die Welt kann deshalb während des Undo-Uploads oder Ordnerwechsels wieder geladen werden. Danach versucht der Sync einen unnötigen Multiverse-Import. Dieser Ablauf erklärt die auffällige Kombination aus erneutem Laden und „World already exists“ sehr gut.

**SEHR WAHRSCHEINLICH: Dieser konkurrierende Ladepfad ist am beschriebenen Restore-Vorfall beteiligt.** Welcher Thread die konkrete Welt zuerst wieder geöffnet hat und welche Generatorinformationen er dabei las, ist ohne vollständige Logs und das damalige Backup nicht bewiesen.

**MÖGLICH: Ein Backup enthält keine brauchbare `level.dat`, oder ein vorzeitig geladener Weltzustand überschreibt die wiederhergestellten Metadaten beim nächsten Speichern.** Beide Risiken werden vom aktuellen Code nicht zuverlässig ausgeschlossen. Sie liefern die zusätzliche Voraussetzung, die für eine tatsächliche Änderung von FLAT zu NOISE notwendig ist.

**NICHT BEWIESEN und für eine intakte bestehende Welt nicht aus dem geprüften Paper-Code ableitbar:** Allein ein `WorldCreator` mit Default NORMAL überschreibt eine lesbare FLAT-`level.dat`. Paper verwendet in diesem Fall die gespeicherten Dimensionen. Der WorldsGUI-Creator im konkurrierenden Sync setzt sogar ausdrücklich FLAT. Deshalb wäre die Aussage „Restore ruft unmittelbar einen NORMAL-Creator auf und zerstört damit FLAT“ hier falsch.

## 1. Konkrete Hauptursache und Codepfad

### Automatische Synchronisierung

`src/main/java/net/clanimg/worldsGUI/WorldsGUI.java:30–31,98,297–302`:

```java
private static final long TICKET_SYNC_INITIAL_DELAY_TICKS = 20L * 2L;
private static final long TICKET_SYNC_INTERVAL_TICKS = 20L * 2L;
// onEnable:
ticketSyncTask = Bukkit.getScheduler().runTaskTimer(
    this, this::syncTicketWorlds,
    TICKET_SYNC_INITIAL_DELAY_TICKS, TICKET_SYNC_INTERVAL_TICKS);
// syncTicketWorlds:
guiManager.syncOpenTicketWorlds();
```

`GuiManager.java:956–985` lädt asynchron `listOpenTicketWorlds()` **und** `listServerWorlds(localServerId)`. Auf dem Hauptthread prüft der Code nur Serverzuordnung und `Bukkit.getWorld(...)`. Ist die Welt nicht geladen, folgt `createWorldFromMetadata(entry)`. Ein Test auf `backupService.isWorldBusy(...)` fehlt.

`GuiManager.java:988–1012`:

```java
WorldCreator creator = buildWorldCreator(worldName, false);
World created = Bukkit.createWorld(creator);
// ...
Bukkit.dispatchCommand(console, "mv import " + worldName + " normal");
```

Auch hier fehlen Restore-Sperre, Trennung zwischen Neuanlage und Laden eines bestehenden Ordners und Generator-Metadaten. Der Name „FromMetadata“ täuscht: Die verwendeten Metadaten enthalten gerade keinen Generator.

### Restore

`BackupRunner.runRestore():272–332`:

```text
Backup nach <welt>.restore-tmp herunterladen/entpacken
→ busyWorlds.add(world)
→ unloadWithRetries()
→ uploadWorld(world, "undo")       [potenziell langer R2-Upload]
→ Ordner <welt> nach <welt>.pre-restore verschieben
→ .restore-tmp nach <welt> verschieben
→ finishRestore()
```

`unloadWithRetries():335–343` prüft das Entladen zu diesem Zeitpunkt. Die Prüfung wird **nach dem Undo-Upload und unmittelbar vor dem Dateitausch nicht wiederholt**.

`GuiManager.BackupHooks.unloadWorldForRestore():4226–4247` evakuiert Spieler, speichert die Welt, versucht `mv unload`, fällt auf `Bukkit.unloadWorld(..., true)` zurück und kontrolliert anschließend `Bukkit.getWorld(...) == null`. Dieser Hook ist grundsätzlich vernünftig; seine Garantie gilt aber nicht dauerhaft.

`BackupRunner.finishRestore():346–362` ruft `hooks.loadWorld(world)` auf.

`GuiManager.BackupHooks.loadWorld():4251–4259`:

```java
if (Bukkit.getWorld(worldName) != null) {
    return true;
}
// ...
Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + worldName);
return Bukkit.getWorld(worldName) != null;
```

Ist die alte Welt inzwischen durch den Sync wieder offen, wird dieser Zustand als erfolgreicher Restore akzeptiert. Es wird nicht bewiesen, dass die zurückgegebene Welt aus den gerade eingespielten Dateien geladen wurde.

### Plausibler Ablauf des Logs

```text
Restore-Worker: Welt entladen, Undo-Backup hochladen
Hauptthread-Sync: Welt fehlt in Bukkit → createWorldFromMetadata
Hauptthread-Sync: Bukkit.createWorld(FLAT-Creator) → Welt laden, Spawn vorbereiten
Hauptthread-Sync: mv import → Welt bereits in Multiverse registriert
Restore-Worker: Ordnerwechsel trotz erneut geladener Welt
Restore-Hook: Bukkit.getWorld != null → Erfolg ohne erneutes Laden
Später: Speichern des bereits geladenen Weltzustands
```

Der letzte Teil ist eine mögliche Folge, kein Nachweis für `WrobelXXL-5051`. Insbesondere muss der geladene Zustand NOISE gewesen sein oder gültige FLAT-Metadaten müssen beim maßgeblichen Laden gefehlt haben. Eine korrekt geladene FLAT-Welt wird durch die Race Condition allein nicht zwangsläufig zu NOISE.

Unter Linux können offene Dateien/Ordner trotz bestehender Handles umbenannt werden. Das macht Dateitausch und weitere Schreibzugriffe einer geladenen Welt gefährlich; ein fehlender Windows-Dateifehler würde dieses Risiko auf dem produktiven Linux-Server nicht ausschließen.

## 2. Wie FLAT tatsächlich zu NOISE werden kann

Der geprüfte [Paper-1.21.11-Code in CraftServer#createWorld](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java#L1093) liest vorhandene Weltdaten und deren Dimensionen. Nur wenn keine Weltdaten verfügbar sind, baut er Dimensionen anhand der Creator-Optionen auf. Das Projekt kompiliert gegen Paper API 1.21.11. Der tatsächlich installierte Leaf-Build und andere 1.21-Unterversionen wurden nicht bereitgestellt; deren identisches Verhalten wird nicht als bewiesen ausgegeben.

[Multiverse-Core 5.8.1](https://github.com/Multiverse/Multiverse-Core/blob/5.8.1/src/main/java/org/mvplugins/multiverse/core/world/WorldManager.java) verwendet beim Laden einen Creator mit Environment, Seed und konfiguriertem Custom-Generator. Der WorldType bleibt Default NORMAL. Ein fehlender Generatorstring lässt den Creator unverändert. Intakte Vanilla-FLAT-Daten bleiben über die gespeicherten Dimensionen erhalten; fehlende Weltdaten können damit als NORMAL neu entstehen. Import einer bereits in Multiverse registrierten Welt scheitert schon an der Registrierung. Import einer nur in Bukkit geladenen, noch nicht registrierten Welt wird dagegen unterstützt. „Bukkit vor Import“ ist deshalb bei einer echten Neuanlage nicht automatisch ein Fehler.

Konkrete Hypothesen:

| Einstufung | Hypothese | Beleg / fehlender Nachweis |
|---|---|---|
| BEWIESEN | Restore und Sync sind nicht gegeneinander geschützt | GuiManager 974–1012; BackupRunner 304–325 |
| SEHR WAHRSCHEINLICH | Sync verursacht den beobachteten erneuten Import | Restore-Hook importiert nicht; Sync lädt und importiert unmittelbar nacheinander; Zeitintervall 2 Sekunden |
| MÖGLICH | Backup enthält bereits ausschließlich NOISE-Metadaten | ZIP-Inhalt fehlt; aktuelle level.dat und level.dat_old beweisen nicht den früheren Archivinhalt |
| MÖGLICH | level.dat wurde beim Archivieren übersprungen | BackupRunner 213–216 ignoriert NoSuchFile/AccessDenied; 230–231 ignoriert alle visitFileFailed-Fehler |
| MÖGLICH | Ein veralteter NOISE-Zustand wird nach Dateitausch weiterverwendet und speichert über zurückgespielte FLAT-Daten | BackupRunner 319–325; BackupHooks 4252–4253; benötigt Laufzeitbeweis |
| MÖGLICH | Wiederaufnahme nach Absturz lädt einen falschen/neu angelegten Ordner | recoverFolders 497–527; Plugin-Start ohne gesicherte Reihenfolge zu Multiverse |
| NICHT BEWIESEN | WorldsGUI schreibt selbst NBT von FLAT auf NOISE um | Kein NBT-Schreibcode vorhanden; Schreiben erfolgt über Server-Save und Dateirestore |
| NICHT BEWIESEN | WorldsGUI setzt Welt-Dateipfade pauschal in Kleinbuchstaben um | Die konkreten Dateipfade behalten den Originalnamen |

Die Zeitstempel 15:08:38 und 15:12:43 am 03.10.2026 passen zum Speichern beim Restore. Sie zeigen den Schreibzeitpunkt, nicht den Zeitpunkt oder Verursacher der ersten Generatoränderung. `level.dat_old` ist nur der vorherige Speicherstand, keine dauerhafte Sicherung der ursprünglichen Weltgenerierung.

## 3. Alle angefragten Wege

| Vorgang | Einstieg und relevante Methoden | Verhalten / Risiko |
|---|---|---|
| Neue private / Ticketwelt | NavCommand → GuiManager.createWorld(), 3318–3488 → buildWorldCreator(), 3523–3536 | Explizit FLAT, Overworld/NORMAL-Environment, keine Strukturen; FLAT-Settings siehe unten |
| VOID über Wizard | executeNavCreateWizard(), 2033–2073 | Lokal WorldType.NORMAL + generator("VoidGen"); Custom-Generator fehlt später im Importbefehl |
| Neuanlage auf anderem Cloud-Server | createWorld(), 3385–3405 → API-Eintrag → Zielserver-Sync | voidWorld/chunkyEnabled/Generator werden nicht mit übertragen; Zielserver erstellt immer mit false/FLAT. Separater bewiesener Metadatenverlust |
| Bestehende verwaltete Welt laden | joinWorldLocally(), 4380–4392; BackupHooks.loadWorld(), 4251–4259 | mv load; normaler Join beachtet Busy-Sperre, Sync nicht |
| Automatisches Laden / vermeintliche Neuanlage | syncOpenTicketWorlds(), 956–985; ensureWorldCreatedFromMetadata(), 1049–1064 | Bukkit.createWorld(FLAT-Creator), anschließend mv import; bestehender Ordner wird nicht gesondert behandelt |
| Import | executeNavMyWorldImport(), 2309–2366 | Falls ungeladen: mv import <name> normal; falls schon geladen: nur API-Registrierung, kein Multiverse-Import. Keine Ermittlung des tatsächlichen Environment/Custom-Generators |
| Klonen | executeNavMyWorldClone(), 1872–1961 | mv clone, nach 20 Ticks ggf. mv load; keine eigene Kopierlogik, keine eigenen Generierungsmetadaten, keine Busy-Prüfung der Quelle |
| Backup erstellen | BackupService.runJob(), 241–259 → BackupRunner.uploadWorld(), 110–147 → zipAndUpload()/writeEntries(), 170–234 | Welt.save(), Autosave aus, Fingerabdruck, ZIP-Datenstrom; Welt bleibt spielbar, Dateien kein garantierter unveränderlicher Snapshot |
| R2 Upload | PartUploadOutputStream, 113–158; WorldsRepository 730–753; API app/backups/main.py 355–406 | Signierte Teil-Uploads; API vervollständigt Multipart-Objekt und speichert Größe/SHA256 |
| R2 Download | WorldsRepository.requestBackupDownload(), 760–773; API app/backups/main.py 425–439 | Signierte GET-URL, Größe, SHA256; R2/API entpackt oder verändert die Weltdateien nicht |
| Entpacken | BackupRunner.downloadAndExtract()/extractZip(), 399–465 | Staging-Verzeichnis, normalisierte ZIP-Pfade, Files.copy(REPLACE_EXISTING), optionale SHA256-Prüfung; keine NBT-/Generator-/level.dat-Pflichtprüfung |
| Vor Restore entladen | BackupRunner.unloadWithRetries(); GuiManager.BackupHooks.unloadWorldForRestore() | Spieler entfernen, save, Multiverse/Bukkit unload, Prüfung; Sync kann Garantie anschließend aufheben |
| Nach Restore laden | BackupRunner.finishRestore() → BackupHooks.loadWorld() | mv load; kein unmittelbarer Bukkit-Creator im Restore; bereits geladene Welt wird ungeprüft akzeptiert |
| Import nach Restore | Nicht Bestandteil von finishRestore()/BackupHooks | Der Import kommt wahrscheinlich aus konkurrierendem Sync, nicht dem eigentlichen Restore-Hook |
| Serverstart | WorldsGUI.onEnable() 44–98; BackupService.start() 95–107; BackupRunner.recoverFolders() 475–531 | Recovery bei Plugin-Start, danach 2s-Sync; keine deklarierte Abhängigkeit/Reihenfolge zu MV/VoidGen |

### FLAT-Definition

`GuiManager.java:138` definiert JSON mit Plains-Biom, features=false, lakes=false und Schichten:

```text
1 × bedrock
64 × dirt
1 × grass_block
```

`buildWorldCreator()` setzt dieses JSON über `.generatorSettings(...)`. Es wird bei bestehenden Welten nicht aus ihrer tatsächlichen level.dat rekonstruiert. **Eine Reparatur darf diese Standardschichten nicht pauschal über bestehende FLAT-Welten legen.** Auch FLAT-Welten können andere Schichten/Biome/Strukturen haben.

`normal` in `mv import ... normal` bezeichnet das Environment (Overworld), nicht WorldType.NORMAL. Vanilla FLAT im NORMAL-Environment ist völlig zulässig.

## 4. Backup-Inhalt und level.dat

`WorldFingerprint.isExcludedFromArchive():24–25` schließt **nur** die am Weltordner-Root liegenden `session.lock` und `uid.dat` aus. `level.dat` und `level.dat_old` sind ausschließlich in `isExcludedFromFingerprint():28–31` ausgenommen. Dieselbe Trennung ist bereits im Einführungscommit `911284b` vorhanden.

| Pfad | Im Archiv vorgesehen? | Einschränkung |
|---|---|---|
| level.dat | Ja | Lesefehler werden still übersprungen |
| level.dat_old | Ja, sofern vorhanden | Lesefehler werden still übersprungen |
| region/, entities/, poi/, data/ | Alle enthaltenen Dateien rekursiv | Fehlgeschlagene Dateien/Unterordner können fehlen |
| DIM-1/, DIM1/ | Alle enthaltenen Dateien rekursiv | Nur wenn sie tatsächlich innerhalb dieses Weltordners liegen |
| Separate <welt>_nether / <welt>_the_end | Nein | Geschwisterordner werden nicht mitgesichert |
| Leere Verzeichnisse | Nein | writeEntries schreibt nur Dateien |
| WorldGuard-Daten unter plugins/WorldGuard | Nein | Gehören nicht zum Weltordner; Schutz wird nach Restore aus API neu aufgebaut |
| Multiverse worlds.yml / globale Bukkit-Generatorzuordnung | Nein | Liegen außerhalb des Weltordners |
| WorldsGUI-API-Weltmetadaten | Kein expliziter Export im ZIP | API-Daten bleiben separat; Generierungsdaten fehlen ohnehin |

**Der komplette Weltordner wird versucht, aber Vollständigkeit wird nicht garantiert.** Ein lesbares `level.dat` ist keine zwingende Bedingung für einen erfolgreichen Upload oder Restore. Eine passende Transport-SHA256 beweist nur, dass das entstandene ZIP unverändert ankam, nicht dass alle Quelldateien enthalten waren. Die Downloadgröße wird im Extractor nicht zusätzlich verglichen.

`Files.copy(zip, file, REPLACE_EXISTING)` schreibt `level.dat` korrekt in den Staging-Ordner zurück, falls der ZIP-Eintrag vorhanden ist. Erst danach wird der Ordner verschoben. Im normalen sequenziellen Restore wird die Zielwelt nicht vor Ende der Extraktion geladen. **Das gilt jedoch nicht für den unabhängigen Sync und die Recovery-/Rollback-Wege.**

Die Auslassung aus dem Fingerabdruck bedeutet außerdem: Ändern sich nur Generator, Seed oder andere level.dat-Daten, kann das automatische Backup als „unverändert“ übersprungen werden. Die API vergleicht diesen Fingerabdruck in `app/backups/main.py:508–519`. Ein semantischer Hash der Generierungsdaten sollte künftig zusätzlich einfließen.

## 5. Case-Sensitivity

Die eigentlichen Pfade in `BackupRunner.worldFolder()/tempFolder()/previousFolder():552–561` verwenden `worldContainer.resolve(world)` ohne toLowerCase. In GuiManager werden bestehende Ordner aus dem ursprünglichen Namen gebildet (`3511`, `3791`, `3839`); Import verwendet trim(), nicht toLowerCase(). Die API bildet den R2-Schlüssel mit dem unveränderten worldName (`app/backups/main.py:535`).

Kleinschreibung wird für Vergleichsschlüssel, GUI-IDs, Suchfilter und `%world_lower%`-/`%region_lower%`-Platzhalter genutzt. Bei leeren post-create-commands führt das nicht zu neuen Ordnern. Beliebige später konfigurierte Commands mit diesen Platzhaltern müssen gesondert geprüft werden.

Multiverse 5.8.1 trennt bei 1.21 den Legacy-Dateinamen von seinem NamespacedKey; siehe [WorldFolderResolver](https://github.com/Multiverse/Multiverse-Core/blob/5.8.1/src/main/java/org/mvplugins/multiverse/core/world/helpers/WorldFolderResolver.java). `minecraft:wrobelxxl-5051` allein beweist daher keinen falsch benannten Linux-Ordner. Prüfen muss man die tatsächlich gespeicherte `read-only.legacy-world-name` und `World#getWorldFolder()`.

**BEWIESEN: Die aktuelle Busy-Sperre ist dagegen case-sensitiv.** `BackupService.isWorldBusy():144–145` nutzt Set.contains(worldName). Ein anders geschriebener API-/Command-Name könnte die Sperre umgehen. Die Dateipfade dürfen bei der Korrektur trotzdem nicht kleingeschrieben werden.

## 6. Restart und Recovery

`plugin.yml:8` enthält `load: POSTWORLD`, aber weder depend noch softdepend für Multiverse-Core/VoidGen. Ihre relative Enable-Reihenfolge wird somit nicht explizit garantiert. Das Projekt hat auch keine compileOnly-Abhängigkeit auf Multiverse; alle Interaktionen erfolgen per Console-Command.

Nach einem normalen Start lädt Multiverse registrierte Auto-Load-Welten. Bei intakter Vanilla-FLAT-level.dat und korrekt zugeordnetem Ordner ist `generator: ''` kein Fehler. Der spätere WorldsGUI-Sync überspringt geladene Welten. Sind sie nicht geladen, verwendet er jedoch den pauschalen FLAT-Creator; bei vorhandener lesbarer level.dat bleibt deren Generator maßgeblich, bei fehlendem Ordner entsteht hingegen eine neue Welt ohne Unterscheidung zwischen Datenverlust und legitimer Neuanlage.

Bei unterbrochenem Restore bestehen weitere **bewiesene Sicherheitslücken**:

- `runRestore():280–282` führt den DONE-Sonderpfad vor busyWorlds.add aus.
- `stopWorldForFolderOperations():540–545` ignoriert den booleschen Entladeerfolg und fängt Fehler nur als Warnung ab.
- `recoverFolders():506–509` kann einen bestehenden Zielweltordner rekursiv löschen, weil er als möglicher Auto-Load-Rest interpretiert wird; die Annahme „leer/neu“ wird nicht bewiesen.
- `rollbackAfterFailedLoad():371–381` ignoriert ebenfalls Entladeerfolg, löscht restore-failed und kann bei fehlendem alten Stand eine falsche Erfolgsaussage hinterlassen.
- `finishRestore():358` löscht den lokalen vorherigen Stand; `runRestore():289` räumt ihn ebenfalls auf.
- `RestoreStateStore.read():51–52` behandelt kaputte/unlesbare Statusdateien wie „kein Restore“; `clear()` ignoriert Löschfehler.

Ein `softdepend` allein löst das Problem **nicht**: Wenn Multiverse vor WorldsGUI bereits eine unvollständige Zielwelt lädt, kommt die Recovery zu spät. Ein dauerhaft sicherer Neustart benötigt einen Start-/Offline-Recovery-Schritt vor Multiverse-Autoload oder eine belastbare Integration, die betroffene Welten bis zur Validierung vom Autoload ausschließt.

## 7. Verantwortlichkeit, gefährdete Welten und Datenrisiko

Die Hauptverantwortung des nachgewiesenen Fehlers liegt im **Welt-Laden/Auto-Sync zusammen mit dem Restore**. Der Backup-Code liefert eine zweite Schwachstelle durch still akzeptierte unvollständige Archive. Der R2-Transport enthält keinen Code zur Änderung des Minecraft-Generators. Der nachfolgende Multiverse-Import ist vor allem ein Hinweis auf den falschen zusätzlichen Ladepfad; sein Fehler repariert oder ändert keine bereits geladene Welt.

Gefährdet sind alle lokal verwalteten Welten, die `listServerWorlds()` oder `listOpenTicketWorlds()` zurückliefert: private Spielerwelten einschließlich WrobelXXL-* und Ticketwelten einschließlich A073/A096/A108/A109. Die acht genannten Namen reichen nicht für eine Einzelprüfung ihrer Archive. Das gegenwärtige minecraft:flat der sieben anderen Welten ist positiv, schließt denselben zukünftigen Fehler jedoch nicht aus.

Zusätzlich gefährdet sind VOID-/Custom-Generator-Welten durch Import ohne Generator-ID und Remote-Neuanlagen durch nicht übertragene Create-Optionen. Importierte NORMAL-/NETHER-/END-Welten brauchen ihr tatsächliches Environment; `normal` darf nicht pauschal verwendet werden.

Durch eine falsche „Reparatur“ gefährdet wären:

- Bestehende Bauten/Chunks durch Löschung, Regeneration oder Ersetzen von Regiondateien.
- Neue Chunkgrenzen durch falsche Schichten, Biome, Seed, Struktursettings oder Custom-Generator-Parameter.
- Entities/POI/Spielerdaten durch nicht zusammengehörige oder unvollständige Snapshots.
- Welt-UUID und externe Plugin-Zuordnungen, weil uid.dat derzeit ausgelassen wird. Ob das auf diesem Server konkrete Referenzen bricht, muss separat geprüft werden.
- Der vorherige Weltstand und Beweisdaten durch automatische Löschung von pre-restore/restore-failed oder Recovery-Zielordnern.

Ein korrekter Generator verhindert weitere falsche neue Chunks. Er macht bereits erzeugte Berge nicht rückgängig. Diese Analyse empfiehlt keine Regeneration und keine Löschung von Region- oder Weltdateien.

## 8. Konkrete Korrektur und Patch

Der separat gelieferte `restore-safety.patch` ist ein **nicht angewendeter, begrenzter Sicherheits-Patch** für die belegte Race Condition und die Archiv-Vollständigkeitslücke. Er ist kein fertiger Ersatz für die gesamte unten entworfene Generator-Metadatenarchitektur.

Er soll:

1. Restore-Sperren auch vor Sync und Create aus Metadaten prüfen und vorhandene Restore-Statusdateien berücksichtigen.
2. Bestehende Weltordner über `mv load` laden, statt sie über den Neuanlage-Creator zu öffnen und erneut zu importieren; bei fehlenden Metadaten abbrechen.
3. Alle nicht ausgeschlossenen Archiv-Lesefehler als Fehler behandeln und eine level.dat voraussetzen.
4. Entladeerfolg nach Undo-Upload erneut kontrollieren; Recovery darf bei fehlgeschlagenem Entladen nicht fortfahren.
5. Vorhandene lokale Weltstände erhalten, statt sie automatisch zu löschen.

Für eine produktionsreife Umsetzung müssen zusätzlich die Generierungsdaten im Staging-Ordner als NBT validiert, Custom-Generator-Metadaten verifiziert und das Autoload beim Serverstart kontrolliert werden. Eine bloße Dateiexistenzprüfung im kleinen Patch ist ausdrücklich kein NBT- oder Generatornachweis. **Den kleinen Patch allein als Erfüllung aller Anforderungen zu deployen wäre verfrüht.**

## 9. Robuste Prävention: vollständiger Entwurf

### Explizite Metadaten

Ein versioniertes Manifest gehört pro Welt in die API und als Datei `worldsgui-generation.yml` in den Weltordner / jedes ZIP. Explizites `generator: null` bedeutet nachweislich Vanilla; ein fehlender Key bedeutet unbekannt und darf nicht als null/NORMAL interpretiert werden.

```yaml
schema-version: 1
world:
  folder-name: WrobelXXL-6265
  key: minecraft:wrobelxxl-6265
  environment: NORMAL
  world-type: FLAT
  generator: null
  generator-settings: '<exakte gespeicherte Settings; keine Standardschichten einsetzen>'
  seed: '<exakter long-Wert>'
  generate-structures: false
  generation-nbt-sha256: '<kanonischer Hash der tatsächlichen WorldGenSettings>'
provenance:
  source: validated-level-dat-and-explicit-create-contract
  server-version: '<konkreter Build>'
```

Für VoidGen steht generator beispielsweise auf `VoidGen` oder der tatsächlich genutzten `Plugin:ID`-Zeichenfolge, world-type auf NORMAL. Ein Bukkit-Custom-Generator ist nicht zuverlässig allein aus Vanilla-WorldGenSettings rekonstruierbar. Das Manifest muss deshalb bereits bei Create aus den ausdrücklich gewählten Optionen entstehen; für Import/Klon werden die tatsächlichen Angaben der Quelle übernommen. Generator-Plugin-Version, BiomeProvider und notwendige Datapacks sollten ebenfalls erfasst werden.

Zu ändern wären tatsächlich vorhandene Schichten:

- Java: WorldEntry, WorldsRepository.insertWorld()/Parser/Persist-Retry, GuiManager.createWorld()/createWorldFromMetadata()/Import/Klon und BackupWorldHooks/BackupRunner.
- API: Welt-Request-/Response-Modelle und Serializer in app/worlds/main.py.
- SQL: beide Welttabellen in app/database.py **und** doppelte Bootstrap-/Migrationspfade in app/worlds/main.py. Bestehende Einträge bleiben zunächst unbekannt; kein Default NORMAL.
- Backup: Manifest im ZIP, Generierungshash im Fingerabdruck, Datei-Inventar mit SHA256/Größen und Snapshot-ID.

### Vertrauenswürdige Bestandsmigration

1. Weltordner und Multiverse-Legacy-Namen exakt zuordnen; case-only-Kollisionen unter Linux explizit ablehnen.
2. Im NBT `Data.WorldGenSettings.dimensions.minecraft:overworld.generator.type` lesen. Ein irgendwo gefundenes minecraft:flat reicht nicht. Bei NETHER/END das tatsächlich gewählte Environment beachten.
3. Seed, komplette Generatorsettings/Dimensionen und Datapacks aus derselben zuverlässigen Sicherung erfassen.
4. Custom-Generator aus ursprünglichem Create-Vertrag, geprüfter Multiverse-/Bukkit-Konfiguration und Laufzeitzustand bestimmen. Ein leeres mv-generator-Feld beweist bei historisch falsch importierten VOID-Welten keinen Vanilla-Ursprung.
5. Widersprüche/unbekannte Angaben markieren und automatisch weder laden noch erstellen noch wiederherstellen.

Für WrobelXXL-5051 dürfen die aktuellen NOISE-Daten nicht unbesehen als ursprünglich korrektes Manifest übernommen werden. Gesucht wird ein nachweislich gesundes älteres Backup oder ursprünglicher Generierungsvertrag.

### Sicherer Ablauf

```text
Auftrag + Originalpfad + Restore-Lock dauerhaft übernehmen
→ ZIP in separates Staging herunterladen
→ Transport-SHA256, ZIP-Inventar, level.dat, NBT und Manifest prüfen
→ konkrete Generation/Environment/Plugin-Verfügbarkeit gegen Quelle validieren
→ Spieler evakuieren, vollständig speichern und sauber entladen
→ Bukkit UND Multiverse-Unloaded-Zustand prüfen
→ unveränderlichen Undo-Snapshot erstellen; Lesen darf nichts überspringen
→ unmittelbar vor Tausch erneut den Unloaded-Zustand prüfen
→ alten Stand in eindeutigem, dauerhaft erhaltenem Ordner aufbewahren
→ validierten Staging-Stand einsetzen, dauerhaftes Journal schreiben
→ NBT/Manifest am endgültigen exakten Pfad erneut validieren
→ registrierte Welt ausschließlich mit verifizierter MV-Konfiguration laden
   ODER unregistrierte Welt direkt mit verifizierten Import-Optionen importieren
→ erwarteten Runtime-Generator / Environment prüfen; erstes Save kontrollieren
→ erst danach WorldGuard/Gamerules anwenden und Spieler freigeben
```

Kein vorheriger Default-Creator für den Import einer wiederhergestellten Welt. Für Vanilla ist die validierte vollständige level.dat maßgeblich, für Custom zusätzlich der explizite Plugin-/ID-Vertrag. Wenn die vorhandene Multiverse-Registrierung nicht zum Backup passt, sicher abbrechen oder ihre geprüften Eigenschaften **vor** dem Laden über die tatsächliche API ändern; nicht geladen importieren oder nachträglich Generator raten.

Tatsächlich vorhandene MV-5.8.1-API-Einstiege sind `WorldManager.createWorld(CreateWorldOptions)`, `importWorld(ImportWorldOptions)`, `loadWorld(LoadWorldOptions)` und `unloadWorld(UnloadWorldOptions)`; siehe [WorldManager-Javadoc](https://multiverse.github.io/Multiverse-Core/javadoc/latest/org/mvplugins/multiverse/core/world/WorldManager.html). ImportWorldOptions bietet environment(...), generator(...) und generatorSettings(...), aber keinen WorldType-Setter; Vanilla-Dimensionen müssen deshalb aus validierten Weltdaten kommen. Es wird kein erfundener importWorld(..., WorldType)-Aufruf vorgeschlagen. Eine Java-Integration erfordert eine echte Multiverse-Dependency im Gradle-Build und deklarierte Plugin-Abhängigkeiten.

### Restart / Fehlerbehandlung

- Restore-Journal enthält Original-/Staging-/Previous-Pfad, Backup-ID, Generation-Vertrag, Dateiinventar und Phasen, nicht nur job-id/phase.
- Unlesbares Journal bedeutet gesperrt/Fehler, nicht „kein Restore“.
- Nach Absturz darf keine betroffene Welt vor Offline-Recovery/Validierung automatisch laden. Dafür ist eine kontrollierte Multiverse-Autoload-Integration bzw. ein Pre-Start-Recovery-Schritt nötig.
- Keine rekursiven Löschungen von Weltständen. Konflikte in eindeutig benannte Quarantänepfade verschieben, bei Entladefehler vollständig abbrechen.
- Nur der Besitzer des Locks darf finish/rollback ausführen. Hauptthread-Aufträge müssen beim Timeout/Shutdown vor späterer Ausführung ebenfalls auf Gültigkeit/Lock geprüft werden.
- Logs enthalten Ladeursprung (SYNC/RESTORE/RECOVERY/JOIN), Phase, Originalpfad, NamespacedKey und Generatorvertrag; keine Tokens oder signierten R2-URLs.

## 10. Verifikation und noch fehlende Beweise

Für die konkrete Ursache von WrobelXXL-5051 fehlen das damals eingespielte ZIP, das unmittelbar vor dem Restore erstellte Undo-ZIP, die vollständigen Logs mit Uhrzeiten und der genaue Leaf-Build. Es wurde kein Live-Server verändert und kein R2-Backup heruntergeladen.

Zur Beweisführung sollten **offline** die beiden ZIPs auf Vorhandensein und Inhalt von level.dat/level.dat_old untersucht werden. Die exakten Overworld-Generatorpfade müssen verglichen werden. Ein Restore-Test darf nur in einer isolierten Kopie stattfinden.

Erforderliche Integrationsprüfungen vor produktiver Freigabe:

- FLAT mit abweichenden Schichten/Biom, Vanilla NORMAL und VoidGen: Backup → Restore → neue Chunks → vollständiger Neustart; Generierungsdaten bleiben identisch.
- Undo-Upload künstlich verzögern und 2s-Sync parallel laufen lassen: kein fremdes Laden zwischen unload und load.
- Fehlende/kaputte level.dat, fehlendes/uneindeutiges Manifest, Generator-Plugin nicht verfügbar, NBT-Widerspruch: Abbruch vor Dateitausch, keine neue NORMAL-Welt.
- Unlesbare Quelldatei im Backup: Upload fehlgeschlagen statt unvollständigem „complete“.
- Absturz in jeder Journalphase und insbesondere zwischen beiden Ordner-Moves: keine Autoload-Neuanlage, keine Löschung des alten Standes.
- Linux mit WrobelXXL-5051 versus wrobelxxl-5051 sowie korrekter Legacy-World-Name: Originalpfad bleibt erhalten.
- Negative Gegenprobe mit unveränderter alter Implementierung: vorzeitiges Laden lässt sich gezielt reproduzieren. Erst dies belegt die Race Condition auch im konkreten Serverlauf.

Die Analyse ist abgeschlossen; der konkrete Vorfall bleibt hinsichtlich der erstmaligen FLAT→NOISE-Änderung offen. Die aufgeführten Codefehler lassen sich unabhängig davon korrigieren.

## 11. Prüfungen des nicht angewendeten Vorschlags

- `git apply --check restore-safety.patch`: erfolgreich; Patch wurde nicht angewendet.
- Alle 53 Java-Quelldateien mit den fünf vorgeschlagenen Ersatzdateien separat über `javac --release 21` kompiliert: erfolgreich. Verwendet wurde der lokale Gradle-Abhängigkeitscache; dies ist kein vollständiger Gradle-/Paper-Integrationstest. Eine bereits vorhandene GameRule.getByName-Deprecationwarnung blieb bestehen.
- Neun isolierte Java-Checks bestanden: persistierter SWAPPING-Status, case-insensitive Status-Erkennung, keine Sperre einer fremden Welt, Abbruch bei ungültiger Phase, Archivfilter für level.dat/level.dat_old/session.lock/uid.dat sowie Gegenprobe für das unveränderte Fingerabdruckproblem.
- Keine Live-Server-, Minecraft-NBT-, R2- oder Linux-Restore-Integration ausgeführt. Die Testfixtures sind künstliche Dateien im Analyseordner, keine echten Minecraft-Welten.
- `git diff -- src build.gradle.kts`: leer. Ausschließlich neue Analyseartefakte unter analysis/ wurden angelegt.

Der kleine Patch wird absichtlich als Zwischenkorrektur vorgelegt. Seine vorhandene Dateiprüfung erkennt fehlende/leere/unlesbare level.dat, aber keine vollständig gelesene, semantisch falsche NOISE-level.dat und keinen fehlenden Custom-Generatorvertrag. Der vollständige Präventionsentwurf in Abschnitt 9 ist erforderlich, um auch diese Fälle sicher abzudecken.
