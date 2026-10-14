# Nächster gezielter Test

Die Diagnose-JAR liegt in `build/libs/WorldsGUI-1.0-SNAPSHOT.jar`. Sie enthält ausschließlich Beobachtung des Create-/Load-Ablaufs; kein Generatorfix ist enthalten.

1. Diese JAR als WorldsGUI auf dem betroffenen Server einsetzen und den Server neu starten. Bei Erstellung auf einem anderen Zielserver dieselbe Diagnose auch dort einsetzen.
2. Im Startlog muss `[WorldGenDebug] START revision=create-trace-v1` erscheinen. Erwartete SHA-256 der gebauten Original-JAR: `7a18f984c150aae552d17ccf9bf604b784612a192b7cdf36909ebfe8dc13201e`. Paper kann intern ein remapptes Artefakt verwenden; dessen protokollierter Pfad und Hash können abweichen.
3. Eine vollständig neue FLAT-Welt über denselben GUI-Weg erstellen. Den tatsächlichen Namen aus `REQUEST` und den tatsächlichen Ordner aus `STATE ... folder=...` verwenden. Der Wizard erzeugt normalerweise `<Spieler>-<Zahl>`.
4. Das Problem in neuen Chunks reproduzieren. Anschließend über die Serverkonsole `save-all flush` ausführen und Logs einschließlich der Save-Snapshots sichern. Die Diagnose selbst erzwingt kein Speichern.
5. Die vollständige `/version`-Ausgabe sowie die Diagnosezeilen vom GUI-Server und gegebenenfalls Zielserver bereitstellen. Beispiel im tatsächlichen Serververzeichnis:

```bash
grep -F '[WorldGenDebug]' logs/latest.log > /tmp/worldsgui-create-debug.log
```

Für eine zusätzliche Dateiprüfung den exakten Pfad aus `STATE` einsetzen, nicht einen geratenen Testweltnamen. Fehlermeldungen nicht unterdrücken:

```bash
WORLD_DIR='/exakter/pfad/aus/STATE'
ls -ld -- "$WORLD_DIR"
ls -l -- "$WORLD_DIR/level.dat"
gzip -t -- "$WORLD_DIR/level.dat"
```

Die integrierte NBT-Auswertung unterscheidet explizit `minecraft:overworld`, Nether und End. Ein `minecraft:noise`-Treffer aus Nether/End beweist keine falsche Overworld-Generierung.

Wichtige Zeilen: `REQUEST`, `CREATE_BEFORE`, `CREATE_AFTER`, `COMMAND_BEFORE`, `WORLD_INIT`, `WORLD_LOAD`, `WORLD_UNLOAD_EVENT`, `WORLD_SAVE_EVENT`, `SAMPLE`, `STATE`. `COMMAND_AFTER dispatchReturned=true` bestätigt nur die Command-Verarbeitung, nicht eine erfolgreiche Weltoperation.

Logs sind auch bei einer bestehenden Konfiguration standardmäßig aktiv. Nach dem Test kann `debug.world-generation: false` in der WorldsGUI-Konfiguration gesetzt und `/worldsgui reload` ausgeführt werden. Optional beschränkt `debug.world-generation-worlds` die Logs auf bestimmte tatsächliche Weltnamen; für den ersten Wizard-Test leer lassen.

Grenzen: Ereignisse vor Aktivierung von WorldsGUI werden nicht erfasst. Die Logs setzen keine Creator-Eigenschaften, verändern keine Dateien und greifen auf keine Chunks zu. Die zusätzlichen lesenden NBT-Abfragen benötigen jedoch Zeit auf dem Hauptthread und können Timing beeinflussen. Das Generatorfeld der Bukkit-World bezeichnet Custom-Generatoren; für Vanilla sind der explizite Overworld-NBT-Pfad und der konkrete Serverbuild entscheidend.
