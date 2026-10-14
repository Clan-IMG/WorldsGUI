from pathlib import Path
root=Path.cwd()
out=root/'analysis/create-generation-2026-10-03'
specs=[
('src/main/resources/guis.yml',467,524),
('src/main/resources/guis.yml',246,261),
('src/main/java/net/clanimg/worldsGUI/listener/InventoryListener.java',1,19),
('src/main/java/net/clanimg/worldsGUI/command/NavCommand.java',39,52),
('src/main/java/net/clanimg/worldsGUI/guiruntime/TriggerDispatcher.java',43,103),
('src/main/java/net/clanimg/worldsGUI/guiruntime/TriggerDispatcher.java',129,147),
('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java',186,213),
('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java',232,254),
('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java',695,715),
('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java',2672,2706),
('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java',2033,2074),
('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java',3318,3537),
('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java',956,1065),
('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java',3660,3759),
('src/main/java/net/clanimg/worldsGUI/gui/GuiManager.java',4943,4987),
]
chunks=['# Originalcode vor Diagnoseinstrumentierung\n\nStand b231e0d. Relevante Methoden vollständig, GUI-/Switch-Einstiege als gekennzeichnete Ausschnitte. Zeilennummern beziehen sich auf diesen Originalstand.\n']
for file,a,b in specs:
    text=(root/file).read_text(encoding='utf-8').splitlines()
    chunks.append(f'\n## {file}, Zeilen {a}–{b}\n\n```java\n'+ '\n'.join(f'{i+1:5}: {text[i]}' for i in range(a-1,min(b,len(text)))) + '\n```\n')
(out/'SOURCE_BEFORE_DEBUG.md').write_text(''.join(chunks),encoding='utf-8')
(root/'src/main/java/net/clanimg/worldsGUI/diagnostics').mkdir(parents=True,exist_ok=True)
