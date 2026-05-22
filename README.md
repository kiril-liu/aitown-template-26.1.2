# AI Town - 智能村民 Mod (Smart Villagers)

# Changelog

All notable changes to this project will be documented in this file.

## [v0.2.0-alpha] - 2026-05-22

### Overview

`v0.2.0-alpha` is the second early alpha version of **AI Town**.

This version expands the project from a single smart builder villager into an early multi-role intelligent villager system. Smart villagers can now build houses, collect materials, chop trees, store resources, craft basic building components, and expose their internal inventory through an in-game UI.

This release marks the beginning of the broader **AI Town** direction: villagers are no longer only passive NPCs or instant-structure generators. They are starting to behave like workers with roles, inventories, tasks, storage logic, and visible states.

---

### Added

#### Smart Builder Villager

- Added the **Villager Chip**, which can turn a normal villager into a smart builder.
- Smart builders can read structure blueprints and place blocks step by step.
- Smart builders can consume materials from:
  - their own hidden villager inventory;
  - nearby storage containers;
  - the temporary "Bluetooth Building Material Chest" system.
- Builders can continue construction based on available materials.
- Builders can report missing materials.
- Builders can be reactivated using chip interaction.

#### Blueprint / Structure Building

- Added support for loading Minecraft structure templates.
- Added support for custom `.nbt` structure files.
- Added support for vanilla structure templates such as village houses.
- Added logic to ignore invalid or special blocks such as structure blocks inside templates.
- Added initial reflection-based support for reading structure template palettes in the current NeoForge environment.

#### Bluetooth Building Material Chest

- Added nearby container scanning for construction materials.
- Smart builders can consume materials from nearby chests instead of relying only on their personal inventory.
- This allows the player to place a material chest near the construction site and let the builder use it automatically.

#### Portable Crafting / Mental Crafting

- Added basic automatic crafting support for builders.
- Builders can convert simple raw materials into required building components when possible.
- Supported examples include:
  - logs to planks;
  - planks to stairs;
  - planks to slabs;
  - planks to doors;
  - planks to fences;
  - cobblestone to cobblestone stairs/slabs/walls;
  - stone to stone bricks;
  - glass to glass panes.
- Added safety checks for missing crafting ingredients to avoid runtime crashes.

#### Smart Lumberjack Villager

- Added the **Lumberjack Chip**, which can turn a normal villager into a smart lumberjack.
- Smart lumberjacks can:
  - search for nearby trees;
  - identify logs using Minecraft log tags;
  - validate nearby leaves to reduce accidental chopping of player buildings;
  - chop logs;
  - collect dropped logs, saplings, sticks, and apples;
  - return resources to nearby home storage.
- Added basic idle behavior for lumberjacks.
- Lumberjacks can enter idle state when:
  - no valid trees are found;
  - inventory is full;
  - no valid storage is available;
  - storage is full.

#### Villager Inventory UI

- Added right-click inventory viewing for smart villagers.
- Players can now inspect the internal inventory of intelligent villagers.
- This makes debugging and managing multiple smart villagers much easier.
- Shift + right-click remains reserved for chip activation and role assignment.
- Normal right-click on a smart villager opens the inventory UI.

#### Role Interaction Improvements

- Improved chip behavior for existing smart villagers.
- Added support for repeated activation through chip interaction.
- Improved role state handling between builder and lumberjack logic.
- Added clearer separation between:
  - normal right-click inspection;
  - Shift + right-click activation.
 
---
### Known Issues

- Villager pathfinding is still mostly based on Minecraft's default navigation and needs further abstraction.
- Builders may still struggle with vertical construction or unreachable positions.
- Lumberjacks may still require improved pickup and deposit behavior.
- Storage binding is not yet explicit; villagers currently search nearby containers.
- Smart villager status display is still basic.
- Personal names, work logs, door signs, mood, hunger, sleep, and trade systems are planned but not yet implemented.
- The current system is still an early alpha and may produce unexpected emergent behavior.

---

### Next Plans

The next development stage will focus on the **Smart Villager Identity and Behavior Core**.

Planned features include:

- unique names for each smart villager;
- citizen IDs;
- unified villager role/status data;
- slower and more natural thinking frequency;
- clearer behavior priority system;
- better pathfinding wrapper;
- better home and storage binding;
- work logs;
- detailed right-click status display;
- foundation for future town simulation systems.

---
*Created by [刘家祥] - Let's build a smarter world!*
