Analyseartefakte, 03.10.2026

ANALYSE.md                Vollständiger Ursachenbericht und Präventionsentwurf
restore-safety.patch      Nicht angewendeter, begrenzter Sicherheits-Patch (5 Dateien)
proposal-src/             Isolierte vorgeschlagene Java-Dateien, Original-src unverändert
AnalysisChecks.java       Isolierte Checks für Status-/Archivfilterverhalten
compiled-proposal/        Separat kompilierte Klassen; kein deploybares Plugin
fixtures/                 Künstliche Testdateien; keine Minecraft-Welten
build_proposal.py          Reproduzierbare Erzeugung des Diff aus Originaldateien
prepare_compile.py        Vorbereitung der isolierten javac-Prüfung
javac.args                Lokale Compilerargumente, nicht für andere Rechner portabel

Der Patch ist kein vollständiger Generator-Metadatenfix. Vor produktiver Verwendung
sind die NBT-/Manifestvalidierung und die kontrollierte Startup-Recovery erforderlich.
Keine JAR wurde erstellt, kein Patch angewendet, kein Server verändert.
