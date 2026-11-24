# Building Auroria Nodes dev.5.11

The released plugin reports version `1.21-26.3-core-dev.5.11-refinery1182-keywarcfg`. The installable asset is `milzzynodes.jar`. The separately downloadable source kit contains the material needed for the core build.

## Build

Use Linux or Linux under WSL, Bash, unzip and JDK 21 to reproduce the shipped class files. JDK 25 can also build and run the tests.

```sh
bash build.sh
```

The script compiles the active Java sources against the bundled API dependencies, runs the key-point war model, configuration migration and refinery tests, and packages the result in `build/milzzynodes.jar`. The build works offline with the files in the source kit.

| Path | Contents |
| --- | --- |
| `core/src/` | Dev.5.11 Java source for the key-point system, refinery, lifecycle integration and map API, alongside retained core patch source. |
| `core/PatchKeyPointJar.java` | Applies the edition to the supplied earlier core. |
| `core/test/` | Tests for one-life battles, respawn sickness, battle outcomes, configuration migration and refinery processing. |
| `build-support/base-dev.5.8.jar` | Precompiled earlier core used as the build input by the original distribution. |
| `build-support/compile-libs.zip` | Compile-time libraries, including the Paper API. |
| `reference/main-nodes/` and `reference/ginjer-nodes/` | Original Kotlin and Java source snapshots, with their notices. |
| `reference/dev.5.3/` and `reference/previous-patches/` | Earlier compatibility and integration changes for maintainers. |
| `docs/defaults/` | Exact plugin descriptor and default configuration extracted from the release JAR. |

The original distribution builds from its supplied binary base. The reference source trees represent different points in the fork’s history. The release asset preserves the supplied dev.5.11 JAR. Compilers can generate different synthetic lambda names, and ZIP entry timestamps can differ between builds.

## Verification

The shipped JAR is 9,937,216 bytes. Its SHA-256 is given below.

```text
ff4b6f5c8addeb5ab7062c561aef0e2ce2a71062b721058cd8491271ebfd10b3
```

The JDK 21 rebuild passed all three bundled test programs. Every uncompressed entry matched the released JAR byte for byte. On JDK 25 the tests also passed, with different synthetic lambda names in one rebuilt class. Paper 1.21.11 build 132 and Paper 26.3 build 256 each loaded the shipped JAR with Vault and a small territory fixture, reached the ready state, answered the staff, battle and refinery command checks, and shut down. These were startup and command checks. Individual Minecraft versions between those two builds have not been tested here.

[Release](https://github.com/Auroria-Productions/Auroria-Nodes/releases/tag/v1.21-26.3-core-dev.5.11) · [Command guide](COMMANDS.md)
