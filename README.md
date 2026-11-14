# EzVisuals (Fabric 1.21.4)
GitHub -> Actions -> Build -> Artifacts -> ezvisuals-jar. Jar goes to .minecraft/mods with Fabric API.
Menu: Right Shift. Tab / Shift+Tab switch tabs inside the menu.

Your own images (no rebuild needed), in .minecraft/config/ezvisuals/ :
  mascot.png            -> anime girl in the menu sidebar
  pictures/1.png..4.png -> Custom Pictures tab

Lua scripts: menu -> Scripts tab.
  - copy Lua code, press Paste (name comes from a "--@name foo" line)
  - or put scripts/ + scripts.json on GitHub, paste the raw URL of scripts.json in "Cloud", press Update
  - folder: .minecraft/config/ezvisuals/scripts/   (changes reload automatically)
  - hooks: onTick(), onHud(dt); API: ez.fps() ez.tps() ez.player() ez.theme() ez.rect() ez.rrect() ez.text() ez.width() ez.size() ez.enabled() ez.setEnabled() ez.msg() ez.log()
