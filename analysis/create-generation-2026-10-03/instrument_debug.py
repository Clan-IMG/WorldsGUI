from pathlib import Path
root=Path.cwd()
path=root/'src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java'
text=path.read_text(encoding='utf-8')
original=text
out=root/'analysis/create-generation-2026-10-03'
(out/'GuiManager.before-debug.java.txt').write_text(original,encoding='utf-8')

def once(a,b):
    global text
    assert text.count(a)==1,(text.count(a),a[:70])
    text=text.replace(a,b,1)

# Preserve original dispatch arguments and return values. The helper logs only world-related commands.
lines=[]
for number,line in enumerate(text.splitlines(keepends=True),1):
    line=line.replace('Bukkit.dispatchCommand(',f'generationDebug.dispatchCommand("GuiManager:{number}", ')
    if 'World created = Bukkit.createWorld(creator);' in line:
        origin='METADATA_CREATE' if number<2000 else 'LOCAL_CREATE'
        line=line.replace('Bukkit.createWorld(creator)',f'generationDebug.createWorld("{origin}", creator)')
    lines.append(line)
text=''.join(lines)
once('import net.clanimg.worldsGUI.data.WorldsRepository;','import net.clanimg.worldsGUI.data.WorldsRepository;\nimport net.clanimg.worldsGUI.diagnostics.WorldGenerationDebug;')
once('    private final BackupService backupService;','    private final BackupService backupService;\n    private final WorldGenerationDebug generationDebug;')
once('        this.plugin = plugin;\n        this.repository = repository;','        this.plugin = plugin;\n        this.repository = repository;\n        this.generationDebug = new WorldGenerationDebug(plugin);')
once('''        String worldName = entry.worldName();
        String worldKey = worldName.toLowerCase(Locale.ROOT);
        String ownerUuid = entry.ownerUuid();''','''        String worldName = entry.worldName();
        generationDebug.request("METADATA_CREATE", worldName, "FLAT_FORCED_BY_METADATA",
            entry.serverName(), resolveLocalServerId(), true);
        String worldKey = worldName.toLowerCase(Locale.ROOT);
        String ownerUuid = entry.ownerUuid();''')
once('''            || isSameServerIdentifier(targetServer, localServer);

        if (!shouldCreateLocally) {''','''            || isSameServerIdentifier(targetServer, localServer);

        generationDebug.request("GUI_OR_COMMAND_CREATE", worldName, voidWorld ? "VOID" : "FLAT",
            targetServer, localServer, shouldCreateLocally);
        if (!shouldCreateLocally) {''')
path.write_text(text,encoding='utf-8',newline='\n')
config=root/'src/main/resources/config.yml'
settings=config.read_text(encoding='utf-8')
assert '\ndebug:' not in settings
settings+='''
# Temporary diagnostics for the next world-generation test. No generator settings are changed.
# Also defaults to true for existing configs without this section; set false after diagnosis.
debug:
  world-generation: true
  # Empty = all worlds. Use actual folder/world names from the REQUEST log if filtering.
  world-generation-worlds: []
'''
config.write_text(settings,encoding='utf-8',newline='\n')
print('Instrumented create calls:',text.count('generationDebug.createWorld('))
print('Observed command dispatch sites:',text.count('generationDebug.dispatchCommand('))
