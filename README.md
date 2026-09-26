# DyeAuras

Paper 1.21.11 plugin: every colored dye gives a potion effect and a colored particle trail.

## Getting the .jar
1. Push this repo to GitHub.
2. Go to the **Actions** tab → latest "Build plugin" run → download the **DyeAuras** artifact.
3. Unzip it and drop `DyeAuras-1.0.0.jar` into your server's `plugins/` folder, then restart.

## Commands
- `/dyeaura list` – show which dye gives what
- `/dyeaura give <player> <color> [amount]` – give a glowing "Aura Dye" (op only)
- `/dyeaura reload` – reload config.yml (op only)

Edit `plugins/DyeAuras/config.yml` to change effects, levels, trail, and rules.
