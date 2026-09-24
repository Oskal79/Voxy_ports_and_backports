# Voxy — Community Ports & Backports

> [!CAUTION]
> ### 🛑 Unofficial Community Build
> **This repository is an independent community project and is NOT affiliated with, endorsed by, or maintained by Cortex or the official [Voxy project](https://github.com/MCRcortex/voxy).**
>
> **Please do not request support from Cortex or upstream Voxy developers for these builds.**  
> If you encounter any bugs, crashes, or issues, please report them directly in this repository's **[Issues](https://github.com/Oskal79/Voxy_ports_and_backports/issues)**.

# **SOURCE CODE IS IN VERSIONED FOLDERS AND NAMED $\color{red}{\textbf{Voxy!}}$**

---

### Overview

Community backports and maintenance builds of [Voxy](https://github.com/MCRcortex/voxy) — the Level-of-Detail (LoD) distant terrain rendering engine for Minecraft. These versions enable playing with extended render distances on Minecraft releases and loaders where official builds are unavailable.

---

### 📦 Supported Versions & Requirements

| Minecraft | Mod Loader | Java | Shader Compatibility | Companion Mods | Status |
|---|---|---|---|---|---|
| **1.16.5** | Forge 36.2+ | **Java 8** | **Complementary Shaders only** | Embeddium 0.3.18+, Oculus 1.4.8+ | Working *(minor water seam)* |
| **1.21.1** | NeoForge 21.1+ | **Java 21** | All Voxy-compatible shaders | Sodium 0.8.13-beta.2+, Iris 1.8.14-beta.1+ | Fully Working |
| **26.1.2** | NeoForge | **Java 25** | All Voxy-compatible shaders | Sodium 0.9.1+, Iris 1.11.3+ | Fully Working |

---

### 📥 Downloads

Ready-to-use mod archives are available under **[Releases](https://github.com/Oskal79/Voxy_ports_and_backports/releases)**:

* **Minecraft 1.16.5 (Forge)**: `voxy-0.2.18-beta+mc1.16.5-forge.jar`
* **Minecraft 1.21.1 (NeoForge)**: `voxy-0.2.18-beta+mc1.21.1-neoforge.jar`
* **Minecraft 26.1.2 (NeoForge)**: `voxy-0.2.18-beta+mc26.1.2-neoforge.jar`

---

### 💡 Key Improvements & Bug Fixes

* **OpenGL Pack Buffer Crash Fix (`GL_PACK_ROW_LENGTH`)**:  
  Fixed a fatal driver crash (`SIGSEGV` in `memmove_avx512`) occurring during world preview icon (`icon.png`) generation and in-game screenshot captures (F2) on heavy modpacks. The row length state is now properly reset prior to reading back pixel data.
* **1.16.5 Forge Compatibility**:  
  Recompiled with Java 8 bytecode compatibility to run seamlessly on Forge 36.2+ with Embeddium and Oculus. Note that only Complementary Shaders (Reimagined / Unbound) are currently supported on this version.
* **1.21.1 NeoForge Port**:  
  Adapted for the 1.21.1 rendering pipeline, linked with Sodium 0.8.13+ and Iris 1.8.14+ with full shaderpack compatibility.
* **26.1.2 NeoForge Port**:  
  Targets Java 25, compatible with Sodium 0.9.1+ and Iris 1.11.3+.

---

### 📁 Repository Structure

* `1.16.5/` — Full source code, build scripts, and libraries for Minecraft 1.16.5 Forge.
* `1.21.1/` — Full source code and reference libraries for Minecraft 1.21.1 NeoForge.
* `26.1.2/` — Full source code and reference libraries for Minecraft 26.1.2 NeoForge.

---

### 📜 Attribution & License

* Original **Voxy** mod developed by [Cortex](https://github.com/MCRcortex/voxy).
* Licensed under the [GNU General Public License v3.0 (GPL-3.0)](https://www.gnu.org/licenses/gpl-3.0.en.html).
