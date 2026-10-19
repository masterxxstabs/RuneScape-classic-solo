# RuneScape Classic Solo

Your own Old School RuneScape private server. You play it in **RuneLite**, and you choose the
**XP, gathering and drop rates** yourself.

It is built from three open-source projects:

| Part | What it does |
|---|---|
| [OpenRune Server](https://github.com/OpenRune/OpenRune-Server) | The game server (OSRS revision 241, written in Kotlin). This repository is a fork of it. |
| **Launcher** (`tools/launcher`) | A desktop app that installs and starts the server, edits rates and opens the game. |
| [RSProx](https://github.com/blurite/rsprox) | Opens the real RuneLite client and points it at your server instead of Jagex's. |

Everything runs on your own computer. Nothing is shared online, and you don't need an account
anywhere.

> [!NOTE]
> This is a fan project for personal and educational use. It is not affiliated with Jagex. The OSRS
> game cache is not stored in this repository; the install step downloads it from
> [OpenRS2](https://archive.openrs2.org).

## What you need

- **Windows or Linux.** RSProx can't connect RuneLite to private servers on macOS.
- **Java 21:** [Adoptium Temurin 21](https://adoptium.net/temurin/releases/?version=21). On
  Windows, tick *"Set JAVA_HOME"* in the installer.
- About 3 GB of free disk space and 8 GB of RAM.

## Quick start

1. Download this repository (**Code → Download ZIP** and unzip it, or `git clone` it).
2. Start the launcher:
   - **Windows:** double-click `Launcher.bat`.
   - **Linux:** run `./launcher.sh` in a terminal.

   The first start builds the launcher, which takes a few minutes.
3. Set your rates on the left side of the launcher (see [Rates](#rates)).
4. Click **Play**. On the first run the launcher:
   1. installs the server: it downloads the OSRS cache and builds everything, which takes
      5-15 minutes;
   2. starts the server and waits until it is online;
   3. downloads and opens **RSProx**.
5. In the RSProx window, set the client to **RuneLite** and the proxy target to
   **OpenRune Solo**, then click **Launch**. RSProx remembers these choices.
6. Log in to RuneLite with **any username and password**. The account is created the first time
   you log in, and it is an admin account (see [Commands](#admin-commands)).

Next time, open the launcher and click **Play**.

## Rates

| Setting | What it changes | Live OSRS |
|---|---|---|
| **XP rate** | Multiplies the experience from everything you do. Presets: 1x, 5x, 10x, 25x, 50x, 100x. | 1 |
| **Gather success** | How likely each woodcutting, mining, fishing or thieving attempt is to succeed. At high values every attempt succeeds, so the action's normal speed becomes the limit. | 1 |
| **Gather yield** | Resources from each successful chop, mine or catch. `2` gives double logs, ore and fish. `1.5` gives an extra resource half of the time. Extras only go into free inventory space. | 1 |
| **Drop rate** | Multiplies the chance of boosted rare drops (2 turns 1/512 into 1/256). | 1 |

**Per-skill rates...** lets you override any of these for single skills, for example 5x
Prayer while everything else stays 25x. Leave a cell empty to use the global rate.

Press **Save rates** to store them. Rates apply when the server starts, so if it is already
running the launcher offers to restart it. Log out in-game first so your progress is saved.

The launcher stores the rates in `game.yml`, and you can edit that file directly too:

```yaml
gameplay:
  xp-rates:
    multiplier: 25.0          # every skill
    skills: { prayer: 5.0 }   # overrides for single skills
  gather-rates:
    success-multiplier: 2.0
    yield-multiplier: 1.5
    skills:
      woodcutting: { success-multiplier: 3.0, yield-multiplier: 2.0 }
  drop-rates:
    multiplier: 1.0
```

`game.example.yml` documents every key. Typos in skill names, and rates of zero or below, stop
the server at startup with a message that says which setting is wrong.

## Admin commands

On this server everyone who logs in is an admin, so you can use `::` commands in the chat box:

| Command | Effect |
|---|---|
| `::item coins 1000000` | Spawns items (by name or id). |
| `::master` / `::reset` | Sets all stats to 99, or back to 1. |
| `::tele 0_50_50_22_18` | Teleports to a coordinate. `::mypos` shows where you are. |
| `::openbank` | Opens your bank. |
| `::god` | Toggles invincibility. |

## Running it without the launcher

```sh
./gradlew install          # first time only: downloads the cache, creates game.yml and RSA keys
./gradlew run              # starts the server; it is ready when it prints "Server ready in ..."
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

Then create `~/.rsprox/proxy-targets.yaml` (on Windows: `%USERPROFILE%\.rsprox\proxy-targets.yaml`).
Replace the modulus with the `Modulus:` value from `.data/client.key`:

```yaml
config:
  - name: OpenRune Solo
    jav_config_url: https://client.blurite.io/jav_local_241.ws
    revision: "241"
    modulus: PASTE_MODULUS_HERE
    varp_count: 15000
    game_server_port: 43594
    export_binaries: false
```

Start RSProx from its [releases page](https://github.com/blurite/rsprox/releases), choose
RuneLite and **OpenRune Solo**, and launch.

To stop the server cleanly, type `stop` into its console, or use the launcher's **Stop server**
button.

## What's in the game

OpenRune is still being built, so not all of OSRS is there yet:

- 20 of the 23 skills work. Agility, Fletching and Hunter are not done yet.
- 23 bosses work, along with a number of quests, shops, the bank, Slayer, the collection log and
  more.

[PROGRESS.md](PROGRESS.md) lists everything that is done.

## Updating from OpenRune

This repository keeps OpenRune's full history, so you can pull in their newest content:

```sh
git remote add upstream https://github.com/OpenRune/OpenRune-Server.git   # once
git fetch upstream
git merge upstream/main
```

After an update that changes the game revision, press **Install / Update** in the launcher.

## Troubleshooting

- **The launcher window doesn't open.** Run `java -version` in a terminal. It must say 21 or
  newer.
- **Install fails while downloading the cache.** OpenRS2 may be busy. Press
  **Install / Update** again.
- **RuneLite says it can't connect.** Check that the launcher shows **Server: Online**, and that
  RSProx has **OpenRune Solo** selected. If you re-installed, press **Play** once more: it
  rewrites the RSProx target with your server's new key.
- **Port 43594 is in use.** Another server is still running. Stop it, or change `game-port` in
  `game.yml` and press **Play** again.
- **Out of memory.** The server is started with up to 4 GB of RAM. Close other programs or
  lower `-Xmx4g` in `tools/launcher/src/main/kotlin/org/rsmod/tools/launcher/ServerController.kt`.

The server's own log is in the launcher window. RSProx writes its log to
`.launcher/rsprox.log`.

## For developers

Server content is written as Kotlin plugins in `content/`. Read [AGENTS.md](AGENTS.md) and the
[OpenRune wiki](https://openrune.github.io/) before changing server code. These files were added on
top of OpenRune:

- `api/player/.../stat/SkillRates.kt`: the XP, gather-success and gather-yield multipliers.
  They are applied in `statAdvance`, `statRandom` and `awardSkillingProduct`.
- `content/other/rates`: loads `gameplay.xp-rates` and `gameplay.gather-rates` from `game.yml`.
- `content/other/console`: stops the server cleanly when `stop` is typed on its console.
- `tools/launcher`: the launcher (Kotlin + Swing). `./gradlew :tools:launcher:run` starts it
  from source.

## Credits and license

The server is [OpenRune Server](https://github.com/OpenRune/OpenRune-Server), which is based on
[RS Mod](https://github.com/rsmod/rsmod), under the ISC license (see [LICENSE.md](LICENSE.md)).
[RSProx](https://github.com/blurite/rsprox) and [RuneLite](https://runelite.net) are separate
projects, each under its own license. Old School RuneScape is a trademark of Jagex Ltd.
