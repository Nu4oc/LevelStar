# LevelStar

LevelStar is a lightweight Spigot/Paper plugin that adds a level system based on experience points gained through PvP. Designed especially for crystal PvP practice servers, it rewards players with points for defeating opponents. Once enough points are collected, players level up, with their levels displayed in customizable colors.

**Features**

- Gain points on kill, automatically added toward leveling.
- Fully configurable levels, points, and colors via config.yml.
- Level-up messages: private or broadcast to the whole server.
- PlaceholderAPI support:
- **%levelstar_level%** → level with color formatting
- **%levelstar_level_raw%** → raw integer level
- Data stored using SQLite.



```
points:
  per_kill: 200
  per_level: 5000
max-level: 2000
min-level: 1
level-format: "{level} ★"
level-up-message: "&aYou leveled up! {display}!"
level-up-message-type: "private"

levels:
  "1-10": "#AAAAAA"
  "11-20": "#00FF00"
  "21-30": "#00AAFF"


```


LevelStar requires Paper/Spigot 1.21+ and optionally supports [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 
 for placeholders. The plugin provides a simple command **/levelstar reload** to reload the configuration, which requires the permission levelstar.reload.
