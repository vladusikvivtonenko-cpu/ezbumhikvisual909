--@name fpsbox
-- Small FPS / TPS box. Hooks: onTick(), onHud(dt)
function onHud(dt)
  local a = ez.theme().accent
  local s = ez.fps() .. " FPS  |  TPS " .. ez.tps()
  local w = ez.width(s) + 12
  ez.rrect(6, 140, w, 16, 4, 0xD00E0E14)
  ez.rect(9, 140, w - 6, 1, a)
  ez.text(s, 12, 144, 0xFFE6E6F0)
end
