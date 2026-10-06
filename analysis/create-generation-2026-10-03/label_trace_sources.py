from pathlib import Path
p=Path('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java')
s=p.read_text(encoding='utf-8')
labels={202:'runtime-gui',1012:'createWorldFromMetadata.import',1442:'luckperms.add',1459:'luckperms.remove',1912:'executeNavMyWorldClone.clone',1921:'executeNavMyWorldClone.load',2296:'auto-create-on-join.load',2332:'executeNavMyWorldImport.import',3425:'createWorld.import',3548:'configureChunkyWorld.world',3549:'configureChunkyWorld.center',3550:'configureChunkyWorld.shape',3551:'configureChunkyWorld.radius',3552:'configureChunkyWorld.start',3644:'joinCreatedWorldWhenReady.load',3782:'deleteWorld',4090:'resolveNextOwnWorld.load',4241:'BackupHooks.unloadWorldForRestore',4257:'BackupHooks.loadWorld',4391:'joinWorldLocally.load',4982:'runPostCreateCommands',5055:'applyConfiguredWorldGuardFlags'}
for line,label in labels.items():
    old=f'"GuiManager:{line}"'
    assert s.count(old)==1,old
    s=s.replace(old,f'"{label}@GuiManager:{line}"',1)
p.write_text(s,encoding='utf-8',newline='\n')
p=Path('src/main/java/net/clanimg/worldsGUI/diagnostics/WorldGenerationDebug.java')
s=p.read_text(encoding='utf-8')
s=s.replace('''            + " levelDatOldExists=" + Files.isRegularFile(folder.resolve("level.dat_old"))''','''            + " levelDatBytes=" + levelDat.toFile().length()
            + " levelDatModifiedMillis=" + levelDat.toFile().lastModified()
            + " levelDatOldExists=" + Files.isRegularFile(folder.resolve("level.dat_old"))''')
p.write_text(s,encoding='utf-8',newline='\n')
p=Path('analysis/create-generation-2026-10-03/prepare_diagnostic_checks.py')
s=p.read_text(encoding='utf-8-sig')
s=s.replace(r'generationDebug\.dispatchCommand\("GuiManager:\d+", ',r'generationDebug\.dispatchCommand\("[^"]+@GuiManager:\d+", ')
p.write_text(s,encoding='utf-8',newline='\n')
