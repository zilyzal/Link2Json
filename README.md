# Link2JSON

Android app that turns download links into a clean JSON file.

- One screen for everything: paste one link, many part links, or a post URL — it adapts automatically
- Post URLs are scanned for the cover image and rar/zip (archive) links only
- Several releases or hosts on one page become separate **batches**; tap to exclude, or "Only" to keep one
- Output is a package-style JSON (cover image, source, packages with parts)
- Optional **name**; if empty it comes from the page title or file name
- Optional file size lookup (Sizes button)
- Paste from clipboard, or share a link to the app from your browser
- Save the `.json` file, share it, or copy it

## Output

```json
{
  "name": "My Game",
  "image": "https://example.com/cover.jpg",
  "source": "https://example.com/my-game",
  "created": "2026-10-22T23:50:00+03:30",
  "totalParts": 2,
  "packages": [
    {
      "name": "My.Game",
      "host": "example.com",
      "type": "multipart",
      "parts": 2,
      "links": [
        { "part": 1, "file": "My.Game.part1.rar", "url": "https://example.com/My.Game.part1.rar" },
        { "part": 2, "file": "My.Game.part2.rar", "url": "https://example.com/My.Game.part2.rar" }
      ]
    }
  ]
}
```

`size`, `mime` and `totalSize` appear after tapping **Sizes**, when the server answers.

## Download

Get **Link2JSON.apk** from the [latest release](../../releases/latest). Requires Android 8.0+.

## Build

Pushes to `main` build and publish the APK through GitHub Actions. Locally: `./gradlew :app:assembleRelease`.
