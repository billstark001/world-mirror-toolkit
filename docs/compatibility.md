# Replay compatibility matrix

The matrix describes the `export-analysis` command. All listed versions use ReplayMod file format 14 and the clientbound configuration/play packet stream. The CLI reads `mcversion` and numeric `protocol` from `metaData.json`; an explicit `--version` must agree. A matching bundled block-state mapping is required.

| Minecraft | Protocol | DataVersion | Chunk / login / respawn packet IDs | Block states | Verification |
| --- | ---: | ---: | --- | ---: | --- |
| 26.1.2 | 775 | 4790 | 45 / 49 / 82 | 29,873 | Full local replay exported: 237,240 events, 8,339 chunks, 23 region files, 22 biome names, no decode warnings. |
| 26.2 | 776 | 4903 | 45 / 49 / 82 | 32,366 | Full supplied replay exported: 1,415,828 events, 6,915 chunks across two worlds, 20 region files, 22 biome names, no decode warnings. |
| 26.3 | 777 | 5023 | 46 / 50 / 84 | 35,723 | Packet registration and layouts inspected in the 26.3 named client jar; mappings generated from that jar. No 26.3 replay was available for an end-to-end export. |

The configuration registry-data packet is ID 7, and finish-configuration is ID 3 in these schemas. The play-state block-entity-data packet is ID 6; start-configuration is ID 118 in 26.1.2/26.2 and ID 120 in 26.3. Classification always includes the protocol state, since IDs are reused across states.

## Feature coverage

| Capability | 26.1.2 | 26.2 | 26.3 |
| --- | --- | --- | --- |
| `.mcpr`/raw ReplayMod input and metadata validation | Verified | Verified | Schema ready; no sample |
| Configuration/play transitions and biome registry | Verified | Verified | Decoder present; no sample |
| Full chunk packets, block states, light, block entities | Verified | Verified | Decoder present; no sample |
| Login/respawn dimension changes | Verified | Verified | Decoder present; no sample |
| Incremental block/entity updates, heightmaps, entities, POI, `level.dat` | Unimplemented | Unimplemented | Unimplemented |

Minecraft 1.21.11 and the `1.20.1` schema template are not bundled as supported replay targets. An external schema alone is insufficient when the packet layout or registry mapping differs.

The 26.2 validation used the supplied recording locally. The replay and generated worlds are kept outside version control. Two different login hashed seeds identify two worlds. The larger has 4,137 overworld and 2,263 Nether chunks; the smaller has 515 overworld chunks. Previous dimension-only merging had combined 478 coordinate collisions into one output and produced visible seams. The Nether dimension type starts at section Y=0, while the overworld starts at section Y=-4. The old export applied -4 to both and shifted the Nether down 64 blocks. All 6,915 output chunks were independently checked for DataVersion, dimension-specific `yPos` and section Y bounds, and absence of `ReplayRecovered.Warnings`.

## Regenerating block mappings

The checked-in `.json.gz` mappings were generated from the named Minecraft jars used by the neighboring mod build. The generator requires JDK 25 for 26.x client jars. Fabric-patched named jars also need Fabric API classes on the dumper classpath. In the mod repository, `:versions:fabric-<version>:printRuntimeClasspath` prints resolved JAR paths; save its output to a file and pass that file as `--fabric-classpath-file`. The generator selects the Fabric API and loader entries, while it obtains Minecraft libraries from the version metadata.

```powershell
# In world-mirror:
.\gradlew.bat :versions:fabric-26.3:printRuntimeClasspath --offline |
  Out-File -Encoding utf8 build\fabric-26.3-classpath.txt

# In world-mirror-toolkit, with the named 26.3 Minecraft JAR path substituted:
.\world-mirror-cli\build\install\world-mirror-cli\bin\world-mirror-cli.bat generate-registry-mappings `
  -v 26.3 --client-jar <named-26.3-minecraft.jar> `
  --java-home <jdk-25-directory> `
  --fabric-classpath-file ..\world-mirror\build\fabric-26.3-classpath.txt `
  -o build\registries_26.3.json
```

The generated DataVersion and block-state count should match the matrix before replacing a bundled resource.
