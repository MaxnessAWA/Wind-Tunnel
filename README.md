# Wind Tunnel

Wind Tunnel is a NeoForge mod for Minecraft 1.21.1 focused on controllable airflow, aircraft testing, and force inspection workflows for the Create Aeronautics stack.

The project provides world-mounted wind tunnels, aircraft-mounted airflow injectors, and a test stand workflow for reading lift, drag, side force, and rotational moments in game.

## Features

- Configurable wind tunnel blocks with adjustable flow length and airspeed
- Wind tunnel controller for tunnel-wide settings and fan state
- Aircraft airflow injector with body-relative and world-relative flow modes
- Wind tunnel mount and mount interface for aircraft binding and pose locking
- Force diagram and force-group inspection UI
- Measurement readouts for lift, drag, side force, pitch, roll, and yaw moments
- Symmetric airfoil test content for aerodynamic experiments

## Target Environment

- Minecraft `1.21.1`
- NeoForge `21.1.219+`
- Java `21`

## Runtime Dependencies

The mod metadata declares these gameplay dependencies:

- Create `6.0.10+`
- Sable `1.0.6+`
- Simulated `1.0.3+`
- Create Aeronautics `1.0.3+`
- LDLib2 `2.2.6+`

## Build

This is a Gradle-based Java project using the NeoForge ModDev plugin.

Typical commands:

```powershell
.\gradlew.bat compileJava
.\gradlew.bat build
.\gradlew.bat runClient
```

Notes:

- The project expects Java `21`.
- Dependency resolution requires access to the public Maven repositories listed in `build.gradle`.
- This repository is intended for mod development use, not as a standalone library jar.

## Repository Layout

- `src/main/java/io/github/windtunnel/` - gameplay code, networking, UI, and integration logic
- `src/main/resources/` - assets, language files, blockstates, models, animations, and mod metadata
- `gradle/` - Gradle wrapper support files

## Project Status

This repository is an early public version and is still under active iteration.

Areas likely to change:

- UI layout and polish
- aerodynamic tuning and balancing
- integration behavior across upstream dependencies
- content scope and block set

## License

This repository is licensed under the [MIT License](LICENSE).

## Disclaimer

This project is not affiliated with the Create, Simulated, Sable, or Create Aeronautics teams.
