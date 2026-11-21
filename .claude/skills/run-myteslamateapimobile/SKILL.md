---
name: run-myteslamateapimobile
description: Build, install, launch and drive the TeslaMate Android app in a local emulator (Windows, Git Bash). Use when asked to run/start the app, see a change in the real app, take a screenshot of a screen (État, Trajets, Charges, Stats, Réglages), tap through the UI, read logcat, or run the unit tests.
---

L'appli se pilote avec `.claude/skills/run-myteslamateapimobile/driver.sh` (Git Bash) : il démarre l'AVD `teslamate`, installe l'APK debug, puis tape, fait défiler et capture l'écran via `adb`. Les chemins sont relatifs à la racine du dépôt. Vérifié sous Windows 11, pas sous Linux.

## Prérequis

JDK 17, Gradle 9.9.1, Android SDK (`ANDROID_HOME`) avec `cmdline-tools/latest` et `platform-tools`. L'hyperviseur Windows doit être actif : l'émulateur utilise WHPX. Une seule fois (télécharge ~2 Go, émulateur + `system-images;android-34;default;x86_64`, crée l'AVD Pixel 6 `teslamate`) :

```bash
bash .claude/skills/run-myteslamateapimobile/driver.sh setup
```

## Lancer et piloter (chemin agent)

```bash
D=.claude/skills/run-myteslamateapimobile/driver.sh
bash $D build      # gradle assembleDebug (~3 min à froid)
bash $D boot       # démarre l'émulateur s'il ne tourne pas, attend sys.boot_completed (~25-40 s)
bash $D install    # adb install -r : garde les données, donc les accès saisis
bash $D launch     # am start -W sur fr.florian.teslamate.debug
bash $D ui         # liste les textes visibles (dump uiautomator)
bash $D tapt Stats && sleep 1 && bash $D ss stats   # tape sur un libellé, capture
```

Les captures vont dans `app/build/run/<nom>.png` (le chemin est affiché ; ouvrir le PNG avec Read et **regarder l'image**). Le dernier dump UI est `app/build/run/ui.xml`, le log de l'émulateur `app/build/run/emulator.log`.

| commande | effet |
|---|---|
| `setup` | installe l'émulateur et l'image, crée l'AVD (rien à refaire si déjà fait) |
| `boot` / `stop` | démarre l'émulateur (fenêtre visible) / l'arrête (`adb emu kill`) |
| `build` / `install` / `launch` | APK debug → émulateur → MainActivity |
| `ui` | textes et content-desc à l'écran |
| `tapt <texte>` | tape au centre du premier nœud dont le texte contient `<texte>` ; code 1 + « introuvable » sinon |
| `tap <x> <y>` | tape en pixels écran (1080x2400) |
| `scroll [down\|up]` | glisser vertical |
| `key <KEY>` | `BACK`, `HOME`, `ENTER`… |
| `text <chaîne>` | saisie clavier dans le champ focalisé (non testé) |
| `ss [nom]` | capture d'écran |
| `logs [n]` | n dernières lignes logcat du process de l'appli |

## Accès à l'API

Au premier lancement, l'appli ouvre Réglages avec un formulaire vide : **c'est l'utilisateur qui saisit l'adresse et le mot de passe dans la fenêtre de l'émulateur**, l'agent ne les tape pas. Ils restent ensuite après `stop`/`boot` et `install` (vérifié). On ne peut pas les injecter par fichier : le secret est chiffré par une clé Keystore créée sur l'appareil. Sans accès, seul l'écran Réglages est utile.

## Chemin humain

`bash $D boot`, puis utiliser la fenêtre de l'émulateur à la souris. Ou bien `"$ANDROID_HOME/emulator/emulator.exe" -avd teslamate`.

## Tests

```bash
gradle testDebugUnitTest
```

Tests JVM purs (calculs), sans émulateur.

## Pièges

- **Git Bash réécrit les chemins POSIX passés à `adb.exe`** : `/sdcard/ui.xml` devient `C:/Program Files/Git/sdcard/ui.xml`. Le driver appelle donc adb avec `MSYS_NO_PATHCONV=1`, et convertit en contrepartie les chemins locaux avec `cygpath -w` (voir `install`). Il faut faire pareil pour tout appel adb écrit à la main.
- **`uiautomator dump` juste après `launch`** renvoie `ERROR: null root node returned by UiTestAutomationBridge` : `find_node` réessaie 5 fois.
- **Les dessins Canvas (heatmap du calendrier, graphiques) n'apparaissent pas dans le dump UI** : `tapt` ne les voit pas, il faut utiliser `tap x y`, en prenant les coordonnées sur une capture. Les captures lues avec Read sont réduites à 900x2000 : multiplier par 1,2.
- **`tapt` ne trouve que ce qui est à l'écran** : après un `scroll down`, les chips du haut (Bilan, Calendrier…) ont disparu ; faire `scroll up` avant.
- Le paquet debug est `fr.florian.teslamate.debug` (applicationIdSuffix), installable à côté de la release.
- `sdkmanager` affiche `Warning: This version only understands SDK XML versions up to 3…` : c'est sans conséquence.
