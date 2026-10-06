from pathlib import Path
import gzip, struct, re
out=Path('analysis/create-generation-2026-10-03')
fixtures=out/'nbt-fixtures'
fixtures.mkdir(exist_ok=True)
def string(s):
    b=s.encode('utf-8')
    return struct.pack('>H',len(b))+b
def payload(t,v):
    if t==1: return struct.pack('>b',v)
    if t==3: return struct.pack('>i',v)
    if t==4: return struct.pack('>q',v)
    if t==8: return string(v)
    if t==9:
        child,items=v
        return bytes([child])+struct.pack('>i',len(items))+b''.join(payload(child,x) for x in items)
    if t==10: return b''.join(bytes([child])+string(name)+payload(child,value) for name,child,value in v)+b'\0'
    if t==11: return struct.pack('>i',len(v))+b''.join(struct.pack('>i',x) for x in v)
    raise ValueError(t)
def compound(*entries): return list(entries)
def generator(kind):
    if kind=='minecraft:flat':
        return compound(('type',8,kind),('settings',10,compound(('biome',8,'minecraft:plains'),('layers',9,(10,[compound(('height',3,1),('block',8,'minecraft:bedrock')),compound(('height',3,64),('block',8,'minecraft:dirt')),compound(('height',3,1),('block',8,'minecraft:grass_block'))])))))
    return compound(('type',8,kind),('settings',8,'minecraft:overworld'))
def world(overworld,nether='minecraft:noise'):
    generation=compound(('seed',4,-1234567890123456),('generate_features',1,0),('dimensions',10,compound(('minecraft:overworld',10,compound(('generator',10,generator(overworld)))),('minecraft:the_nether',10,compound(('generator',10,generator(nether)))),('minecraft:the_end',10,compound(('generator',10,generator('minecraft:noise')))))))
    # Include unrelated numeric arrays/lists to exercise correct skipping.
    return bytes([10])+string('')+payload(10,compound(('Data',10,compound(('Unrelated',11,[1,2,3]),('Another',9,(8,['minecraft:flat','ignored'])),('WorldGenSettings',10,generation),('LevelName',8,'GeneratorTest123')))))
for name,value in [('flat.dat',world('minecraft:flat')),('noise.dat',world('minecraft:noise')),('flat-only-nether.dat',world('minecraft:noise','minecraft:flat'))]:
    (fixtures/name).write_bytes(gzip.compress(value))
(fixtures/'missing-generation.dat').write_bytes(gzip.compress(bytes([10])+string('')+payload(10,compound(('Data',10,compound(('NotWorldGenSettings',8,'minecraft:flat')))))))
(fixtures/'not-gzip.dat').write_bytes(b'not a level.dat')
(fixtures/'truncated.dat').write_bytes(gzip.compress(world('minecraft:flat')[:-12]))
(fixtures/'oversized.dat').write_bytes(gzip.compress(b'x'*(16*1024*1024+2)))
(fixtures/'negative-array.dat').write_bytes(gzip.compress(bytes([10])+string('')+bytes([11])+string('bad')+struct.pack('>i',-1)+b'\0'))
(fixtures/'duplicate-generation.dat').write_bytes(gzip.compress(bytes([10])+string('')+payload(10,compound(('Data',10,compound(('WorldGenSettings',10,[]),('WorldGenSettings',10,[])))))))

original=(out/'GuiManager.before-debug.java.txt').read_text(encoding='utf-8')
current=Path('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java').read_text(encoding='utf-8')
current=re.sub(r'generationDebug\.dispatchCommand\("[^"]+@GuiManager:\d+", ', 'Bukkit.dispatchCommand(', current)
current=re.sub(r'generationDebug\.createWorld\("(?:LOCAL|METADATA)_CREATE", creator\)', 'Bukkit.createWorld(creator)', current)
for addition in [
'import net.clanimg.worldsGUI.diagnostics.WorldGenerationDebug;\n',
'    private final WorldGenerationDebug generationDebug;\n',
'        this.generationDebug = new WorldGenerationDebug(plugin);\n',
'        generationDebug.request("METADATA_CREATE", worldName, "FLAT_FORCED_BY_METADATA",\n            entry.serverName(), resolveLocalServerId(), true);\n',
'        generationDebug.request("GUI_OR_COMMAND_CREATE", worldName, voidWorld ? "VOID" : "FLAT",\n            targetServer, localServer, shouldCreateLocally);\n',
]:
    assert addition in current,addition
    current=current.replace(addition,'',1)
assert current==original, 'Unexpected non-diagnostic change in GuiManager'
print('PASS: Original GuiManager recovered exactly after removing instrumentation')
print('Generated 9 NBT fixtures')
