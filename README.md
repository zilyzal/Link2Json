# Link2JSON

Android app that turns download links into a clean JSON file.

- Optional **name**; if left empty it is derived from the file name
- **Single link** or **Multi-part** (many links for one download), with natural sorting of `part2` / `part10`
- Optional **file size & type lookup** for every link
- Paste from clipboard, or share a link to the app from your browser
- Save the `.json` file, share it, or copy it

## Output

```json
{
  "name": "My Game",
  "type": "multipart",
  "parts": 2,
  "totalSize": 2147483648,
  "created": "2026-10-22T16:03:00+03:30",
  "links": [
    { "part": 1, "file": "My.Game.part1.rar", "url": "https://example.com/My.Game.part1.rar", "size": 1073741824 },
    { "part": 2, "file": "My.Game.part2.rar", "url": "https://example.com/My.Game.part2.rar", "size": 1073741824 }
  ]
}
```

`size`, `mime` and `totalSize` appear only when *Fetch file sizes* is on and the server answers.

## Download

Get **Link2JSON.apk** from the [latest release](../../releases/latest). Requires Android 8.0+.

## Build

Pushes to `main` build and publish the APK through GitHub Actions. Locally: `./gradlew :app:assembleRelease`.
