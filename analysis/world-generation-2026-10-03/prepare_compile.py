from pathlib import Path
root = Path.cwd()
out = root / 'analysis/world-generation-2026-10-03'
cache = Path('C:/Users/Micha/.gradle/caches/modules-2/files-2.1')
jars = sorted(str(p) for p in cache.rglob('*.jar') if not p.name.endswith(('-sources.jar', '-javadoc.jar')))
args = ['-encoding', 'UTF-8', '-proc:none', '--release', '21', '-d', str(out / 'compiled-proposal'), '-classpath', ';'.join(jars)]
for original in sorted((root / 'src/main/java').rglob('*.java')):
    proposal = out / 'proposal-src' / original.relative_to(root)
    args.append(str(proposal if proposal.exists() else original))
(out / 'javac.args').write_text('\n'.join('"' + arg.replace('\\', '/') + '"' for arg in args), encoding='utf-8')
print('Compile inputs:', len(list((root / 'src/main/java').rglob('*.java'))), 'Java source files')
