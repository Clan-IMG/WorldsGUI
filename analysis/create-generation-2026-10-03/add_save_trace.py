from pathlib import Path
p=Path('src/main/java/net/clanimg/worldsGUI/diagnostics/WorldGenerationDebug.java')
s=p.read_text(encoding='utf-8')
s=s.replace('import org.bukkit.event.world.WorldLoadEvent;','import org.bukkit.event.world.WorldLoadEvent;\nimport org.bukkit.event.world.WorldSaveEvent;')
anchor='''    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(WorldUnloadEvent event) {'''
assert s.count(anchor)==1
s=s.replace(anchor,'''    @EventHandler(priority = EventPriority.MONITOR)
    public void onSave(WorldSaveEvent event) {
        World world = event.getWorld();
        observe(() -> {
            if (matches(world.getName())) {
                lifecycle("WORLD_SAVE_EVENT", world, "");
                // The event does not prove that level.dat has already been written.
                Bukkit.getScheduler().runTaskLater(plugin, () -> observe(() -> {
                    if (matches(world.getName())) {
                        snapshot("AFTER_SAVE_EVENT", world.getName());
                    }
                }), 1L);
            }
        });
    }

'''+anchor)
p.write_text(s,encoding='utf-8',newline='\n')
